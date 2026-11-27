package com.netsight.gateway.video;

import com.netsight.gateway.core.ConfigHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 视频端点配置存储：conf/video-endpoints.json。
 * <p>与 mapping.json 分离，避免 MappingSync 重写清单时覆盖视频配置。</p>
 */
@Slf4j
@Component
 public class VideoEndpointStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<VideoEndpoint> endpoints = new ArrayList<>();

    public VideoEndpointStore(ConfigHolder configHolder) {
        this.configHolder = configHolder;
        reload();
    }

    public synchronized void reload() {
        Path p = path();
        if (!Files.exists(p)) {
            endpoints = new ArrayList<>();
            return;
        }
        try {
            Document doc = mapper.readValue(Files.readString(p), Document.class);
            endpoints = doc.getEndpoints() == null ? new ArrayList<>() : new ArrayList<>(doc.getEndpoints());
        } catch (Exception e) {
            log.error("video-endpoints.json 解析失败: {}", e.getMessage(), e);
            endpoints = new ArrayList<>();
        }
    }

    public List<VideoEndpoint> list() {
        return Collections.unmodifiableList(new ArrayList<>(endpoints));
    }

    public VideoEndpoint find(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        return endpoints.stream()
                .filter(e -> deviceCode.equals(e.getDeviceCode()))
                .findFirst().orElse(null);
    }

    public synchronized VideoEndpoint save(VideoEndpoint endpoint) {
        endpoint.setUpdatedAt(LocalDateTime.now().format(FMT));
        List<VideoEndpoint> next = new ArrayList<>(endpoints);
        next.removeIf(e -> endpoint.getDeviceCode().equals(e.getDeviceCode()));
        next.add(endpoint);
        endpoints = next;
        persist();
        return endpoint;
    }

    public synchronized boolean delete(String deviceCode) {
        List<VideoEndpoint> next = new ArrayList<>(endpoints);
        boolean removed = next.removeIf(e -> deviceCode.equals(e.getDeviceCode()));
        if (removed) {
            endpoints = next;
            persist();
        }
        return removed;
    }

    private void persist() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Document doc = new Document();
            doc.setVersion(1);
            doc.setUpdatedAt(LocalDateTime.now().format(FMT));
            doc.setEndpoints(endpoints);
            Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
            try {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 本地开发
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入 video-endpoints.json 失败: " + e.getMessage(), e);
        }
    }

    private Path path() {
        return Paths.get(configHolder.getConfDir(), "video-endpoints.json");
    }

    @Data
    public static class Document {
        private int version = 1;
        private String updatedAt;
        private List<VideoEndpoint> endpoints = new ArrayList<>();
    }
}
