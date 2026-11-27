package com.netsight.gateway.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZLMediaKit REST API 客户端。
 * <p>仅由本机 nams-agent 调用；ZLM API 通过 secret 校验，管理页不直接暴露 secret。</p>
 */
@Slf4j
@Component
public class ZlmClient {

    public static final String APP = "live";
    public static final String VHOST = "__defaultVhost__";

    private final VideoSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public ZlmClient(VideoSettings settings) {
        this.settings = settings;
    }

    public boolean isHealthy() {
        if (!settings.isUsable()) {
            return false;
        }
        try {
            JsonNode r = get("/index/api/getServerConfig", Map.of());
            return r != null && r.path("code").asInt(-1) == 0;
        } catch (Exception e) {
            log.debug("ZLM 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 添加 RTSP 拉流代理，只开 FLV 输出，TCP 传输。
     */
    public AddResult addStreamProxy(String stream, String rtspUrl, boolean tcp) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vhost", VHOST);
        params.put("app", APP);
        params.put("stream", stream);
        params.put("url", rtspUrl);
        // 媒体面只保留 HTTP-FLV 播放；HLS/MP4/TS/fMP4 关闭。
        // 注意：enable_rtsp/enable_rtmp 不能置 0，否则 ZLM 可能不注册源流；
        // RTSP/RTMP 服务端端口在 config.ini 中设为 0，不对外提供。
        params.put("enable_hls", "0");
        params.put("enable_mp4", "0");
        params.put("enable_ts", "0");
        params.put("enable_fmp4", "0");
        // 0=TCP，1=UDP；摄像头默认 TCP，跨 NAT/丢包更稳
        params.put("rtp_type", tcp ? "0" : "1");
        try {
            JsonNode r = get("/index/api/addStreamProxy", params);
            if (r == null) {
                return AddResult.fail(true, "ZLM API 无响应");
            }
            int code = r.path("code").asInt(-1);
            if (code != 0) {
                String msg = r.path("msg").asText("");
                // 不打印完整 URL（可能带摄像头凭据）
                log.warn("ZLM addStreamProxy 失败: code={}, msg={}", code, msg);
                boolean auth = msg.contains("401") || msg.toLowerCase().contains("unauthorized")
                        || msg.contains("认证") || msg.contains("密码");
                return AddResult.fail(false, msg.isEmpty() ? "ZLM 拉流失败" : msg, auth);
            }
            JsonNode data = r.path("data");
            String key = data.path("key").asText("");
            return AddResult.ok(key.isBlank() ? "" : key);
        } catch (Exception e) {
            log.warn("ZLM addStreamProxy 异常: {}", e.getMessage());
            return AddResult.fail(true, e.getMessage());
        }
    }

    public boolean isStreamAlive(String stream) {
        try {
            JsonNode r = get("/index/api/getMediaList", Map.of("vhost", VHOST, "app", APP));
            if (r == null || r.path("code").asInt(-1) != 0) {
                return false;
            }
            JsonNode data = r.path("data");
            if (!data.isArray()) {
                return false;
            }
            for (JsonNode item : data) {
                if (stream.equalsIgnoreCase(item.path("stream").asText(""))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void closeStream(String stream) {
        try {
            get("/index/api/close_streams",
                    Map.of("vhost", VHOST, "app", APP, "stream", stream, "force", "1"));
        } catch (Exception e) {
            log.debug("ZLM close_streams 失败: {}", e.getMessage());
        }
    }

    /**
     * 清理上一次进程残留的虚拟流。只处理 v_ 前缀，避免误删人工推流。
     */
    public void closeVirtualStreams() {
        closeVirtualStreams(java.util.Set.of());
    }

    /** 清理残留虚拟流，但跳过当前活跃会话的流，避免启动清理误杀刚建立的预览。 */
    public void closeVirtualStreams(java.util.Set<String> keep) {
        try {
            JsonNode r = get("/index/api/getMediaList", Map.of("vhost", VHOST, "app", APP));
            if (r == null || r.path("code").asInt(-1) != 0) {
                return;
            }
            JsonNode data = r.path("data");
            if (!data.isArray()) {
                return;
            }
            for (JsonNode item : data) {
                String stream = item.path("stream").asText("");
                if (stream.startsWith("v_") && !keep.contains(stream)) {
                    closeStream(stream);
                    log.info("已清理残留视频流: {}", stream);
                }
            }
        } catch (Exception e) {
            log.warn("清理残留视频流失败: {}", e.getMessage());
        }
    }

    private JsonNode get(String path, Map<String, String> params) throws Exception {
        VideoSettings.VideoConfig cfg = settings.get();
        StringBuilder url = new StringBuilder(cfg.getZlmApiBase().replaceAll("/+$", ""))
                .append(path)
                .append("?secret=").append(encode(cfg.getZlmSecret()));
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append('&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return null;
        }
        return mapper.readTree(resp.body());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public record AddResult(boolean success, String key, boolean zlmUnavailable,
                            boolean authFailure, String detail) {
        public static AddResult ok(String key) {
            return new AddResult(true, key, false, false, "");
        }

        public static AddResult fail(boolean zlmUnavailable, String detail) {
            return new AddResult(false, "", zlmUnavailable, false, detail);
        }

        public static AddResult fail(boolean zlmUnavailable, String detail, boolean authFailure) {
            return new AddResult(false, "", zlmUnavailable, authFailure, detail);
        }
    }
}
