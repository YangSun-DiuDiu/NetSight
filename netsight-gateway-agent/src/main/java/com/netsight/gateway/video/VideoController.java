package com.netsight.gateway.video;

import com.netsight.gateway.common.R;
import com.netsight.gateway.common.ResultCode;
import com.netsight.gateway.sync.MappingDevice;
import com.netsight.gateway.sync.MappingSync;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地视频预览接口，前缀 /local/api/video。
 * <p>云端不参与：仅边缘局域网内完成 RTSP→HTTP-FLV 预览。</p>
 */
@Slf4j
@RestController
@RequestMapping("/local/api/video")
public class VideoController {

    private final VideoEndpointStore endpointStore;
    private final VideoSessionService sessionService;
    private final VideoSecretCipher cipher;
    private final VideoSettings settings;
    private final ZlmClient zlmClient;
    private final MappingSync mappingSync;

    public VideoController(VideoEndpointStore endpointStore, VideoSessionService sessionService,
                           VideoSecretCipher cipher, VideoSettings settings,
                           ZlmClient zlmClient, MappingSync mappingSync) {
        this.endpointStore = endpointStore;
        this.sessionService = sessionService;
        this.cipher = cipher;
        this.settings = settings;
        this.zlmClient = zlmClient;
        this.mappingSync = mappingSync;
    }

    /** 已配置端点（密码脱敏） */
    @GetMapping("/endpoints")
    public R<List<Map<String, Object>>> endpoints() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VideoEndpoint e : endpointStore.list()) {
            list.add(safeEndpoint(e));
        }
        return R.ok(list);
    }

    /** 新增/更新端点配置 */
    @PutMapping("/endpoints/{deviceCode}")
    public R<Map<String, Object>> save(@PathVariable String deviceCode,
                                       @RequestBody VideoEndpointRequest req) {
        MappingDevice device = findDevice(deviceCode);
        if (device == null) {
            return R.fail(ResultCode.BAD_REQUEST, "设备不存在: " + deviceCode);
        }
        if (!isVideoDevice(device)) {
            return R.fail(ResultCode.BAD_REQUEST, "只有摄像头/NVR 可以配置视频预览");
        }
        String error = validate(req);
        if (error != null) {
            return R.fail(ResultCode.BAD_REQUEST, error);
        }
        VideoEndpoint endpoint = endpointStore.find(deviceCode);
        if (endpoint == null) {
            endpoint = new VideoEndpoint();
            endpoint.setDeviceCode(deviceCode);
        }
        endpoint.setDeviceName(device.getDeviceName());
        endpoint.setEnabled(req.getEnabled() == null || req.getEnabled());
        endpoint.setVendor(req.getVendor() == null ? "custom" : req.getVendor());
        endpoint.setHost(req.getHost().trim());
        endpoint.setPort(req.getPort() == null ? 554 : req.getPort());
        endpoint.setChannel(req.getChannel() == null ? 1 : req.getChannel());
        endpoint.setUsername(trim(req.getUsername()));
        endpoint.setMainPath(trim(req.getMainPath()));
        endpoint.setSubPath(trim(req.getSubPath()));
        endpoint.setTransport("udp".equalsIgnoreCase(req.getTransport()) ? "udp" : "tcp");
        endpoint.setPtzEnabled(Boolean.TRUE.equals(req.getPtzEnabled()));
        if (req.isClearPassword()) {
            endpoint.setPasswordEnc("");
        } else if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            endpoint.setPasswordEnc(cipher.encrypt(req.getPassword()));
        }
        endpointStore.save(endpoint);
        sessionService.closeByDevice(deviceCode);
        log.info("视频端点已保存: device={}, vendor={}, host={}, port={}, channel={}",
                deviceCode, endpoint.getVendor(), endpoint.getHost(),
                endpoint.getPort(), endpoint.getChannel());
        return R.ok(safeEndpoint(endpoint));
    }

    @DeleteMapping("/endpoints/{deviceCode}")
    public R<Void> delete(@PathVariable String deviceCode) {
        sessionService.closeByDevice(deviceCode);
        endpointStore.delete(deviceCode);
        return R.ok(null);
    }

    /** 开始/复用预览 */
    @PostMapping("/preview")
    public R<VideoSessionService.VideoPreview> preview(@RequestBody PreviewRequest req,
                                                       HttpServletRequest request) {
        if (req.getDeviceCode() == null || req.getDeviceCode().isBlank()) {
            return R.fail(ResultCode.BAD_REQUEST, "deviceCode 不能为空");
        }
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":"
                + settings.get().getPublicHttpPort();
        try {
            return R.ok(sessionService.preview(req.getDeviceCode(), req.getProfile(), baseUrl));
        } catch (VideoSessionService.VideoException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("视频预览启动异常: {}", e.getMessage(), e);
            return R.fail(ResultCode.VIDEO_ZLM_UNAVAILABLE, "视频预览启动异常");
        }
    }

    @DeleteMapping("/session/{sessionId}")
    public R<Void> close(@PathVariable String sessionId) {
        sessionService.close(sessionId);
        return R.ok(null);
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", settings.get().isEnabled());
        data.put("configured", settings.isUsable());
        data.put("zlmHealthy", zlmClient.isHealthy());
        data.put("activeSessions", sessionService.activeCount());
        data.put("maxSessions", settings.get().getMaxSessions());
        data.put("sessions", sessionService.listSessions());
        return R.ok(data);
    }

    /** ZLM 播放鉴权钩子；LocalAuthFilter 仅允许 127.0.0.1 访问 */
    @PostMapping("/hook/on_play")
    public Map<String, Object> onPlay(@RequestBody Map<String, Object> body) {
        String stream = str(body.get("stream"));
        String params = str(body.get("params"));
        log.debug("ZLM on_play hook: stream={}, tokenPresent={}", stream, params != null && !params.isEmpty());
        if (sessionService.authorizePlay(stream, params)) {
            return hookOk();
        }
        log.info("视频播放鉴权失败: stream={}, params={}", stream, params);
        return Map.of("code", 401, "msg", "invalid video token");
    }

    @PostMapping("/hook/none_reader")
    public Map<String, Object> noneReader(@RequestBody Map<String, Object> body) {
        sessionService.onNoneReader(str(body.get("stream")));
        // 返回 close=true，让 ZLM 停止 RTSP 拉流，避免会话已回收但仍在空转占带宽
        return Map.of("code", 0, "msg", "success", "close", true);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> hookOk() {
        return Map.of("code", 0, "msg", "success");
    }

    private Map<String, Object> safeEndpoint(VideoEndpoint e) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("deviceCode", e.getDeviceCode());
        item.put("deviceName", e.getDeviceName());
        item.put("enabled", e.isEnabled());
        item.put("configured", e.isConfigured());
        item.put("vendor", e.getVendor());
        item.put("host", e.getHost());
        item.put("port", e.getPort());
        item.put("channel", e.getChannel());
        item.put("username", e.getUsername());
        item.put("passwordSet", e.getPasswordEnc() != null && !e.getPasswordEnc().isBlank());
        item.put("mainPath", e.getMainPath());
        item.put("subPath", e.getSubPath());
        item.put("transport", e.getTransport());
        item.put("ptzEnabled", e.isPtzEnabled());
        item.put("updatedAt", e.getUpdatedAt());
        return item;
    }

    private MappingDevice findDevice(String code) {
        return mappingSync.getDevices().stream()
                .filter(d -> code.equals(d.getDeviceCode())).findFirst().orElse(null);
    }

    private static boolean isVideoDevice(MappingDevice d) {
        return "camera".equals(d.getDeviceType()) || "nvr".equals(d.getDeviceType());
    }

    private static String validate(VideoEndpointRequest req) {
        if (req.getHost() == null || req.getHost().isBlank()) {
            return "RTSP 主机地址不能为空";
        }
        if (!req.getHost().trim().matches("[A-Za-z0-9.-]{1,64}")) {
            return "RTSP 主机地址格式不正确";
        }
        int port = req.getPort() == null ? 554 : req.getPort();
        if (port < 1 || port > 65535) {
            return "RTSP 端口必须在 1-65535";
        }
        int channel = req.getChannel() == null ? 1 : req.getChannel();
        if (channel < 1 || channel > 256) {
            return "通道号必须在 1-256";
        }
        String vendor = req.getVendor() == null ? "custom" : req.getVendor();
        if (!List.of("hikvision", "dahua", "custom").contains(vendor)) {
            return "视频厂商只支持 hikvision/dahua/custom";
        }
        if ("custom".equals(vendor)
                && (req.getMainPath() == null || req.getMainPath().isBlank())
                && (req.getSubPath() == null || req.getSubPath().isBlank())) {
            return "自定义厂商至少需要填写主码流或子码流路径";
        }
        return null;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Data
    public static class PreviewRequest {
        private String deviceCode;
        private String profile = "main";
    }
}
