package com.netsight.gateway.video;

import com.netsight.gateway.core.ConfigHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 视频媒体面设置：conf/video-settings.json。
 * <p>包含 ZLM API 地址与 secret；secret 不写入代码仓库。</p>
 */
@Slf4j
@Component
public class VideoSettings {

    private final ConfigHolder configHolder;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile VideoConfig data = new VideoConfig();

    public VideoSettings(ConfigHolder configHolder) {
        this.configHolder = configHolder;
        reload();
    }

    public synchronized void reload() {
        Path p = Paths.get(configHolder.getConfDir(), "video-settings.json");
        if (!Files.exists(p)) {
            log.info("video-settings.json 不存在，视频功能按未配置处理: {}", p);
            return;
        }
        try {
            data = mapper.readValue(Files.readString(p), VideoConfig.class);
            if (data.getMaxSessions() <= 0) {
                data.setMaxSessions(8);
            }
            if (data.getStartTimeoutMs() <= 0) {
                data.setStartTimeoutMs(3000);
            }
        } catch (Exception e) {
            log.error("video-settings.json 解析失败: {}", e.getMessage(), e);
        }
    }

    public VideoConfig get() {
        return data;
    }

    public boolean isUsable() {
        return data.isEnabled()
                && data.getZlmApiBase() != null && !data.getZlmApiBase().isBlank()
                && data.getZlmSecret() != null && !data.getZlmSecret().isBlank();
    }

    @Data
    public static class VideoConfig {
        private boolean enabled = true;
        private String zlmApiBase = "http://127.0.0.1:8000";
        private String zlmSecret = "";
        private int maxSessions = 8;
        private int startTimeoutMs = 5000;
        /** ZLM HTTP-FLV 对外端口（局域网） */
        private int publicHttpPort = 8000;
        /** ZLM 无人观看回收延迟，秒；同时写入 ZLM config.ini */
        private int noneReaderDelaySec = 60;
    }
}
