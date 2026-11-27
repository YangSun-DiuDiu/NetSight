package com.netsight.gateway.video;

import lombok.Data;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 单个摄像头的本地视频接入配置。
 * <p>密码以 {@code passwordEnc} 形式落盘（AES-GCM），不在本对象中保存明文。</p>
 */
@Data
public class VideoEndpoint {

    private String deviceCode;
    private String deviceName;
    private boolean enabled = true;
    /** hikvision | dahua | custom */
    private String vendor = "custom";
    private String host;
    private int port = 554;
    /** 摄像头/NVR 通道号，从 1 开始 */
    private int channel = 1;
    private String username;
    private String passwordEnc;
    /** custom 模式下的主/子码流路径，必须包含前导 / */
    private String mainPath;
    private String subPath;
    private String transport = "tcp";
    private boolean ptzEnabled = false;
    private String updatedAt;

    public boolean isConfigured() {
        return enabled && host != null && !host.isBlank();
    }

    /**
     * 生成 RTSP 拉流地址。密码只在此处短暂参与 URL 构造，不写日志。
     */
    public String buildRtspUrl(String profile, String plainPassword) {
        String path = resolvePath(profile);
        if (path == null || path.isBlank()) {
            return null;
        }
        StringBuilder userInfo = new StringBuilder();
        if (username != null && !username.isBlank()) {
            userInfo.append(encodeUserInfo(username));
            if (plainPassword != null && !plainPassword.isBlank()) {
                userInfo.append(':').append(encodeUserInfo(plainPassword));
            }
            userInfo.append('@');
        }
        return "rtsp://" + userInfo + host + ":" + port + path;
    }

    public String resolvePath(String profile) {
        boolean sub = "sub".equalsIgnoreCase(profile);
        if ("hikvision".equalsIgnoreCase(vendor)) {
            // 海康通道号规则：通道 1 主码流 101、子码流 102；通道 2 为 201/202
            int code = Math.max(1, channel) * 100 + (sub ? 2 : 1);
            return "/Streaming/Channels/" + code;
        }
        if ("dahua".equalsIgnoreCase(vendor)) {
            return "/cam/realmonitor?channel=" + Math.max(1, channel) + "&subtype=" + (sub ? 1 : 0);
        }
        String custom = sub ? subPath : mainPath;
        if (custom == null || custom.isBlank()) {
            return null;
        }
        return custom.startsWith("/") ? custom : "/" + custom;
    }

    public boolean isTcp() {
        return transport == null || "tcp".equalsIgnoreCase(transport);
    }

    private static String encodeUserInfo(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
