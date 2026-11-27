package com.netsight.gateway.video;

import com.netsight.gateway.common.ResultCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 视频预览会话：同一摄像头同一码流复用一条 ZLM 拉流代理。
 * <p>会话键为 deviceCode:profile；关闭预览/无人观看时回收。</p>
 */
@Slf4j
@Service
public class VideoSessionService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VideoEndpointStore endpointStore;
    private final VideoSecretCipher cipher;
    private final VideoSettings settings;
    private final ZlmClient zlmClient;

    private final Map<String, VideoSession> sessions = new ConcurrentHashMap<>();

    public VideoSessionService(VideoEndpointStore endpointStore, VideoSecretCipher cipher,
                               VideoSettings settings, ZlmClient zlmClient) {
        this.endpointStore = endpointStore;
        this.cipher = cipher;
        this.settings = settings;
        this.zlmClient = zlmClient;
    }

    /**
     * 开始/复用预览。
     *
     * @param publicBaseUrl 形如 http://192.168.1.199:8000，由当前管理页请求推导
     */
    public synchronized VideoPreview preview(String deviceCode, String profile, String publicBaseUrl) {
        if (!settings.isUsable()) {
            throw new VideoException(ResultCode.VIDEO_ZLM_UNAVAILABLE,
                    "视频媒体服务未配置或未启动");
        }
        String p = normalizeProfile(profile);
        VideoEndpoint endpoint = endpointStore.find(deviceCode);
        if (endpoint == null || !endpoint.isConfigured()) {
            throw new VideoException(ResultCode.VIDEO_NOT_CONFIGURED,
                    "该设备尚未配置 RTSP 视频地址");
        }

        String key = deviceCode + ":" + p;
        VideoSession existing = sessions.get(key);
        if (existing != null && zlmClient.isStreamAlive(existing.getStream())) {
            existing.setLastAccessAt(now());
            return toPreview(existing);
        }
        if (existing != null) {
            sessions.remove(key, existing);
            zlmClient.closeStream(existing.getStream());
        }

        if (sessions.size() >= settings.get().getMaxSessions()) {
            sweepStale();
        }
        if (sessions.size() >= settings.get().getMaxSessions()) {
            throw new VideoException(ResultCode.VIDEO_MAX_SESSIONS,
                    "已达到最大并发预览路数（" + settings.get().getMaxSessions() + " 路）");
        }

        String plainPassword = cipher.decrypt(endpoint.getPasswordEnc());
        String rtspUrl = endpoint.buildRtspUrl(p, plainPassword);
        if (rtspUrl == null) {
            throw new VideoException(ResultCode.VIDEO_NOT_CONFIGURED,
                    "该设备未配置 " + ("sub".equals(p) ? "子码流" : "主码流") + " RTSP 路径");
        }

        String stream = streamName(deviceCode, p);
        ZlmClient.AddResult result = zlmClient.addStreamProxy(stream, rtspUrl, endpoint.isTcp());
        if (!result.success() && result.detail() != null
                && result.detail().toLowerCase().contains("already exists")) {
            // ZLM 可能残留同名的无轨道源（上次失败/进程未清干净），强制清理后重试一次
            log.warn("ZLM 已存在同名视频流，清理后重试: stream={}", stream);
            zlmClient.closeStream(stream);
            result = zlmClient.addStreamProxy(stream, rtspUrl, endpoint.isTcp());
        }
        if (!result.success()) {
            if (result.zlmUnavailable()) {
                throw new VideoException(ResultCode.VIDEO_ZLM_UNAVAILABLE,
                        "视频媒体服务不可用");
            }
            if (result.authFailure()) {
                throw new VideoException(ResultCode.VIDEO_AUTH_FAILED,
                        "摄像头 RTSP 认证失败，请检查用户名/密码");
            }
            throw new VideoException(ResultCode.VIDEO_SOURCE_UNREACHABLE,
                    "摄像头 RTSP 拉流失败，请检查地址、端口、网络或码流路径");
        }
        String proxyKey = result.key();

        long deadline = System.currentTimeMillis() + settings.get().getStartTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            if (zlmClient.isStreamAlive(stream)) {
                VideoSession session = new VideoSession();
                session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
                session.setDeviceCode(deviceCode);
                session.setProfile(p);
                session.setStream(stream);
                session.setZlmKey(proxyKey);
                session.setToken(UUID.randomUUID().toString().replace("-", ""));
                session.setCreatedAt(now());
                session.setLastAccessAt(now());
                session.setExpiresAt(LocalDateTime.now().plusMinutes(30).format(FMT));
                session.setPlayUrl(publicBaseUrl.replaceAll("/+$", "")
                        + "/" + ZlmClient.APP + "/" + stream + ".live.flv?token=" + session.getToken());
                sessions.put(key, session);
                log.info("视频预览已启动: device={}, profile={}, stream={}", deviceCode, p, stream);
                return toPreview(session);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        zlmClient.closeStream(stream);
        throw new VideoException(ResultCode.VIDEO_START_TIMEOUT,
                "摄像头已连接但未在 " + settings.get().getStartTimeoutMs() + "ms 内产生可播放画面");
    }

    public synchronized void close(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.entrySet().removeIf(e -> {
            if (sessionId.equals(e.getValue().getSessionId())) {
                zlmClient.closeStream(e.getValue().getStream());
                log.info("视频预览已停止: device={}, stream={}",
                        e.getValue().getDeviceCode(), e.getValue().getStream());
                return true;
            }
            return false;
        });
    }

    /** 配置变更/删除设备时，关闭该设备的所有预览会话 */
    public synchronized void closeByDevice(String deviceCode) {
        sessions.entrySet().removeIf(e -> {
            if (deviceCode.equals(e.getValue().getDeviceCode())) {
                zlmClient.closeStream(e.getValue().getStream());
                return true;
            }
            return false;
        });
    }

    /**
     * ZLM on_play 钩子：校验播放 token，避免局域网内直接猜流名盗看。
     */
    public synchronized boolean authorizePlay(String stream, String params) {
        VideoSession session = findByStream(stream);
        if (session == null) {
            return false;
        }
        String token = parseQuery(params).get("token");
        if (token == null || !token.equals(session.getToken())) {
            return false;
        }
        session.setLastAccessAt(now());
        return true;
    }

    public synchronized void onNoneReader(String stream) {
        sessions.entrySet().removeIf(e -> {
            if (stream.equals(e.getValue().getStream())) {
                log.info("无人观看，释放视频流: device={}, stream={}",
                        e.getValue().getDeviceCode(), stream);
                return true;
            }
            return false;
        });
    }

    /** 清理 ZLM 已不存在的会话，避免失效会话占满并发上限。 */
    private void sweepStale() {
        sessions.entrySet().removeIf(e -> {
            if (!zlmClient.isStreamAlive(e.getValue().getStream())) {
                log.info("清理失效视频会话: device={}, stream={}",
                        e.getValue().getDeviceCode(), e.getValue().getStream());
                return true;
            }
            return false;
        });
    }

    public int activeCount() {
        return sessions.size();
    }

    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VideoSession s : sessions.values()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("sessionId", s.getSessionId());
            item.put("deviceCode", s.getDeviceCode());
            item.put("profile", s.getProfile());
            item.put("stream", s.getStream());
            item.put("createdAt", s.getCreatedAt());
            item.put("lastAccessAt", s.getLastAccessAt());
            list.add(item);
        }
        return list;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        // 不阻塞主启动；ZLM 未就绪时失败不影响心跳/采集
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
                // 只清理启动前残留的流；若启动后已有用户点开预览，必须保留
                java.util.Set<String> keep = sessions.values().stream()
                        .map(VideoSession::getStream).collect(java.util.stream.Collectors.toSet());
                zlmClient.closeVirtualStreams(keep);
            } catch (Exception e) {
                log.warn("启动清理视频流失败: {}", e.getMessage());
            }
        }, "video-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private VideoSession findByStream(String stream) {
        if (stream == null) {
            return null;
        }
        return sessions.values().stream().filter(s -> stream.equals(s.getStream())).findFirst().orElse(null);
    }

    private static VideoPreview toPreview(VideoSession s) {
        VideoPreview p = new VideoPreview();
        p.setSessionId(s.getSessionId());
        p.setPlayUrl(s.getPlayUrl());
        p.setToken(s.getToken());
        p.setExpiresAt(s.getExpiresAt());
        p.setStream(s.getStream());
        p.setProfile(s.getProfile());
        return p;
    }

    private static String normalizeProfile(String profile) {
        return "sub".equalsIgnoreCase(profile) ? "sub" : "main";
    }

    private static String streamName(String deviceCode, String profile) {
        String safe = deviceCode.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase(java.util.Locale.ROOT);
        return "v_" + safe + "_" + profile;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        String q = raw.startsWith("?") ? raw.substring(1) : raw;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                try {
                    map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    @Data
    public static class VideoSession {
        private String sessionId;
        private String deviceCode;
        private String profile;
        private String stream;
        private String zlmKey;
        private String token;
        private String playUrl;
        private String createdAt;
        private String lastAccessAt;
        private String expiresAt;
    }

    @Data
    public static class VideoPreview {
        private String sessionId;
        private String playUrl;
        private String token;
        private String expiresAt;
        private String stream;
        private String profile;
    }

    public static class VideoException extends RuntimeException {
        private final int code;

        public VideoException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
