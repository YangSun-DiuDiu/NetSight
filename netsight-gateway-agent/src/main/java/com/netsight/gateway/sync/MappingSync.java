package com.netsight.gateway.sync;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MappingSync {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<MappingDevice> devices = new ArrayList<>();
    private volatile long lastFileMtime = 0L;
    private volatile String version = "-";
    private volatile long revision = 0L;
    private volatile String updatedAt = "-";

    public MappingSync(ConfigHolder configHolder, LocalInventoryStore store, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.store = store;
        this.runtimeState = runtimeState;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        reloadLocal();
    }

    @Scheduled(fixedDelay = 60000)
    public void scheduledSync() {
        try {
            Path p = store.getPath();
            long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
            if (mtime != lastFileMtime || devices.isEmpty()) {
                reloadLocal();
            }
        } catch (Exception e) {
            runtimeState.setLastSyncResult(now() + " 本地清单检测失败: " + e.getMessage());
            log.error("本地清单检测失败", e);
        }
    }

    public synchronized String reloadLocal() {
        try {
            LocalInventoryDocument doc = store.read();
            this.devices = new ArrayList<>(doc.getDevices());
            this.version = doc.getVersion();
            this.revision = doc.getRevision();
            this.updatedAt = doc.getUpdatedAt();
            writeTargets();
            this.lastFileMtime = Files.exists(store.getPath())
                    ? Files.getLastModifiedTime(store.getPath()).toMillis() : 0L;
            String msg = String.format("本地清单已加载（version=%s，revision=%d，设备 %d 台）",
                    version, revision, devices.size());
            runtimeState.setLastSyncResult(now() + " " + msg);
            log.info("{}", msg);
            return msg;
        } catch (Exception e) {
            String msg = "本地清单加载失败: " + e.getMessage();
            runtimeState.setLastSyncResult(now() + " " + msg);
            log.error("{}", msg, e);
            return msg;
        }
    }

    private void writeTargets() throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MappingDevice d : devices) {
            if (d.getDeviceIp() == null || d.getDeviceIp().isBlank()) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("targets", List.of(d.getDeviceIp()));
            Map<String, String> sourceLabels = d.getLabels() == null ? Map.of() : d.getLabels();
            Map<String, String> labels = new LinkedHashMap<>(sourceLabels);
            labels.put("device_code", d.getDeviceCode());
            labels.put("device_name", d.getDeviceName());
            labels.put("device_ip", d.getDeviceIp());
            labels.put("device_type", d.getDeviceType());
            if (d.getLocation() != null && !d.getLocation().isBlank()) {
                labels.put("device_location", d.getLocation());
            }
            e.put("labels", labels);
            entries.add(e);
        }
        Path dir = Paths.get(configHolder.getTargetsDir());
        Files.createDirectories(dir);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
        Files.writeString(dir.resolve("ping.json"), json);
        log.info("targets 文件已生成: {}（{} 条）", dir.resolve("ping.json"), entries.size());
    }

    public List<MappingDevice> getDevices() {
        return devices;
    }

    public long getRevision() {
        return revision;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getVersion() {
        return version;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
