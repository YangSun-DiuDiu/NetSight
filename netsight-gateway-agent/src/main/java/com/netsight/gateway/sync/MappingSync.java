package com.netsight.gateway.sync;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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

/**
 * 采集清单同步：mapping.json（云端下发）→ Prometheus file_sd targets 文件。
 * <p>
 * 设备增删零重启：云端改清单 → 本组件检测变更 → 重写 conf/targets/*.json →
 * Prometheus file_sd（refresh_interval 60s）自动生效。
 * </p>
 */
@Slf4j
@Component
public class MappingSync {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<MappingDevice> devices = new ArrayList<>();
    private volatile long lastFileMtime = 0L;
    private volatile String version = "-";

    public MappingSync(ConfigHolder configHolder, CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    /** 启动时同步一次 */
    @jakarta.annotation.PostConstruct
    public void init() {
        sync();
    }

    /** 每 60s 同步：云端拉取优先，否则本地文件变更检测 */
    @Scheduled(fixedDelay = 60000)
    public void scheduledSync() {
        sync();
    }

    public synchronized String sync() {
        try {
            String result = null;
            // 1) 优先尝试云端拉取（云端 v1.1.7 提供 GET /edge/config/mapping）
            String remote = cloudClient.fetchMapping();
            if (remote != null && !remote.isBlank()) {
                result = parseAndApply(remote, true);
            }
            // 2) 云端不可用则走本地文件（变更检测）
            if (result == null) {
                Path p = mappingPath();
                if (Files.exists(p)) {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime != lastFileMtime || devices.isEmpty()) {
                        String local = Files.readString(p);
                        result = parseAndApply(local, false);
                        lastFileMtime = mtime;
                    } else {
                        result = "本地清单无变更（mtime 相同）";
                    }
                } else {
                    result = "mapping.json 不存在: " + p;
                    log.warn("{}", result);
                }
            }
            runtimeState.setLastSyncResult(time() + " " + result);
            return result;
        } catch (Exception e) {
            String msg = "清单同步失败: " + e.getMessage();
            log.error("{}", msg, e);
            runtimeState.setLastSyncResult(time() + " " + msg);
            return msg;
        }
    }

    private String parseAndApply(String json, boolean fromCloud) throws Exception {
        JsonNode root = mapper.readTree(json);
        // 兼容云端 R 统一返回包装 {code,msg,data:{version,devices}}——data 存在且 code=200 时解包内层
        if (root.path("code").asInt(200) == 200 && root.path("data").isObject()) {
            root = root.path("data");
        }
        version = root.path("version").asText("-");
        JsonNode arr = root.path("devices");
        List<MappingDevice> list = new ArrayList<>();
        for (JsonNode n : arr) {
            MappingDevice d = new MappingDevice();
            d.setDeviceCode(n.path("deviceCode").asText());
            d.setDeviceName(n.path("deviceName").asText());
            d.setDeviceIp(n.path("deviceIp").asText());
            d.setDeviceType(n.path("deviceType").asText());
            d.setLocation(n.path("location").asText());
            d.setCollectType(n.path("collectType").asText("snmp"));
            d.setCollectPort(n.path("collectPort").asInt(161));
            // labels 透传（tenant_id/gateway_code 等业务标签）
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode ls = n.path("labels");
            if (ls.isObject()) {
                ls.properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
            }
            d.setLabels(labels);
            list.add(d);
        }
        this.devices = list;
        writeTargets();
        String src = fromCloud ? "云端" : "本地";
        String msg = String.format("清单已同步（%s，version=%s，设备 %d 台）", src, version, list.size());
        log.info("{}", msg);
        return msg;
    }

    /**
     * 生成 Prometheus file_sd targets 文件：conf/targets/snmp.json。
     * 视频/门禁探针 targets 预留（后续版本输出 camera.json / door.json）。
     */
    private void writeTargets() throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MappingDevice d : devices) {
            if ("snmp".equalsIgnoreCase(d.getCollectType())) {
                Map<String, Object> e = new LinkedHashMap<>();
                List<String> targets = List.of(d.getDeviceIp() + ":" + d.getCollectPort());
                e.put("targets", targets);
                Map<String, String> labels = new LinkedHashMap<>(d.getLabels());
                labels.put("device_code", d.getDeviceCode());
                labels.put("device_name", d.getDeviceName());
                labels.put("device_ip", d.getDeviceIp());
                labels.put("device_type", d.getDeviceType());
                if (d.getLocation() != null && !d.getLocation().isBlank()) {
                    labels.put("device_location", d.getLocation());
                }
                labels.put("__metrics_path__", "/snmp");
                labels.put("__param_module", "netsight_if_mib");
                e.put("labels", labels);
                entries.add(e);
            }
        }
        Path dir = Paths.get(configHolder.getTargetsDir());
        Files.createDirectories(dir);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
        Path file = dir.resolve("snmp.json");
        Files.writeString(file, json);
        log.info("targets 文件已生成: {}（{} 条）", file, entries.size());
    }

    private Path mappingPath() {
        String mp = configHolder.getConfig().getMappingPath();
        if (mp != null && !mp.isBlank()) {
            return Paths.get(mp);
        }
        return Paths.get(configHolder.getConfDir(), "mapping.json");
    }

    public List<MappingDevice> getDevices() {
        return devices;
    }

    public String getVersion() {
        return version;
    }

    private static String time() {
        return LocalDateTime.now().format(FMT);
    }

    @Data
    public static class MappingDevice {
        private String deviceCode;
        private String deviceName;
        private String deviceIp;
        private String deviceType;
        private String location;
        private String collectType = "snmp";
        private int collectPort = 161;
        private Map<String, String> labels = new LinkedHashMap<>();
    }
}
