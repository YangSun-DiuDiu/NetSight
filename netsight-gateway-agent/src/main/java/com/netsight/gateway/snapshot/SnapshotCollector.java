package com.netsight.gateway.snapshot;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import com.netsight.gateway.sync.MappingSync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态快照采集与上报：每 30s 聚合设备状态 → POST /edge/report/status。
 * <p>
 * 数据源二选一（config.json prometheus.source）：
 * prometheus —— 查询网关本地 Prometheus（device_up / device_line_abnormal）；
 * probe（预留）—— 自研探针内存上报（国产化替换路径，指标不出内网）。
 * 查询异常时跳过上报并记录，避免误报离线。
 * </p>
 */
@Slf4j
@Component
public class SnapshotCollector {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final CloudClient cloudClient;
    private final MappingSync mappingSync;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public SnapshotCollector(ConfigHolder configHolder, CloudClient cloudClient,
                             MappingSync mappingSync, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.cloudClient = cloudClient;
        this.mappingSync = mappingSync;
        this.runtimeState = runtimeState;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Scheduled(fixedDelay = 30000)
    public void collectAndReport() {
        List<MappingSync.MappingDevice> list = mappingSync.getDevices();
        if (list.isEmpty()) {
            log.debug("无纳管设备，跳过快照");
            return;
        }
        try {
            List<Map<String, Object>> devices = new ArrayList<>();
            for (MappingSync.MappingDevice d : list) {
                SnapshotResult r = queryDevice(d);
                if (r == null) {
                    // 查询异常：跳过该设备，不误报离线
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deviceCode", d.getDeviceCode());
                item.put("up", r.up);
                item.put("lineAbnormal", r.lineAbnormal);
                devices.add(item);

                RuntimeState.DeviceState st = new RuntimeState.DeviceState();
                st.setDeviceCode(d.getDeviceCode());
                st.setDeviceName(d.getDeviceName());
                st.setDeviceIp(d.getDeviceIp());
                st.setDeviceType(d.getDeviceType());
                st.setLocation(d.getLocation());
                st.setUp(r.up);
                st.setLineAbnormal(r.lineAbnormal);
                st.setCollectedAt(LocalDateTime.now().format(FMT));
                runtimeState.upsertDevice(st);
            }
            if (devices.isEmpty()) {
                log.warn("本次快照无可用设备状态（Prometheus 查询全部失败？）");
                runtimeState.markSnapshot(false);
                return;
            }
            String body = CloudClient.buildSnapshotBody(null, devices);
            boolean ok = cloudClient.sendSnapshot(body);
            runtimeState.markSnapshot(ok);
            if (!ok) {
                runtimeState.setLastError("状态快照上报失败（云端不可达）");
            } else {
                runtimeState.setLastError("-");
                log.debug("状态快照上报成功：{} 台设备", devices.size());
            }
        } catch (Exception e) {
            log.error("快照采集异常: {}", e.getMessage(), e);
            runtimeState.markSnapshot(false);
        }
    }

    /** 查询单设备状态：up 与 lineAbnormal。返回 null 表示查询异常/无数据。 */
    private SnapshotResult queryDevice(MappingSync.MappingDevice d) {
        String source = configHolder.getConfig().getPrometheus().getSource();
        if ("probe".equalsIgnoreCase(source)) {
            // 自研探针数据源：从本地状态文件读取（探针进程/采集软件周期性写入），
            // 文件格式 {"devices":[{"deviceCode":"...","up":true,"lineAbnormal":false}]}
            return queryProbeFile(d);
        }
        String base = configHolder.getConfig().getPrometheus().getBaseUrl();
        try {
            boolean up = queryScalar(base, "device_up", d.getDeviceCode());
            boolean line = queryScalar(base, "device_line_abnormal", d.getDeviceCode());
            SnapshotResult r = new SnapshotResult();
            r.up = up;
            r.lineAbnormal = line;
            return r;
        } catch (Exception e) {
            log.warn("查询设备 {} 状态失败: {}", d.getDeviceCode(), e.getMessage());
            return null;
        }
    }

    /** probe 数据源：读本地状态文件（热可改，模拟器/探针共用同一格式） */
    private SnapshotResult queryProbeFile(MappingSync.MappingDevice d) {
        try {
            Path file = Paths.get(configHolder.getConfig().getPrometheus().getProbeStatusFile());
            if (!Files.exists(file)) {
                log.debug("探针状态文件不存在: {}", file);
                return null;
            }
            JsonNode root = mapper.readTree(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode devices = root.path("devices");
            if (devices.isArray()) {
                for (JsonNode node : devices) {
                    if (d.getDeviceCode().equals(node.path("deviceCode").asText())) {
                        SnapshotResult r = new SnapshotResult();
                        r.up = node.path("up").asBoolean(true);
                        r.lineAbnormal = node.path("lineAbnormal").asBoolean(false);
                        return r;
                    }
                }
            }
            return null; // 文件里没有该设备：跳过，不误报离线
        } catch (Exception e) {
            log.warn("读取探针状态文件失败: {}", e.getMessage());
            return null;
        }
    }

    /** 查询 Prometheus 即时向量标量值：匹配到且值为 1 → true */
    private boolean queryScalar(String base, String metric, String deviceCode) throws Exception {
        String url = base.replaceAll("/+$", "") + "/api/v1/query?query="
                + java.net.URLEncoder.encode(metric + "{device_code=\"" + deviceCode + "\"}", java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(configHolder.getConfig().getPrometheus().getQueryTimeoutMs()))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode result = root.path("data").path("result");
        if (result.isArray() && result.size() > 0) {
            JsonNode value = result.get(0).path("value");
            return value.isArray() && value.size() > 1 && "1".equals(value.get(1).asText());
        }
        return false;
    }

    private static class SnapshotResult {
        boolean up;
        boolean lineAbnormal;
    }
}
