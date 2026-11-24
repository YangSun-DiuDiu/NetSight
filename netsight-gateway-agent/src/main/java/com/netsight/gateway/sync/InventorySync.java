package com.netsight.gateway.sync;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InventorySync {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long[] BACKOFF_SECONDS = {15, 30, 60, 120, 300};

    private final ConfigHolder configHolder;
    private final LocalInventoryStore store;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean pending = true;
    private volatile boolean allowEmptyOnce = false;
    private volatile long nextAttemptAt = 0L;
    private volatile long lastSuccessAt = 0L;
    private int failCount = 0;

    public InventorySync(ConfigHolder configHolder, LocalInventoryStore store,
                         CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.store = store;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    public void triggerNow() {
        triggerNow(false);
    }

    public void triggerNow(boolean allowEmpty) {
        if (allowEmpty) {
            allowEmptyOnce = true;
        }
        pending = true;
        nextAttemptAt = 0L;
    }

    @Scheduled(fixedDelay = 15000)
    public void tick() {
        if (!pending && System.currentTimeMillis() < nextAttemptAt) {
            return;
        }
        long interval = intervalMs();
        if (!pending && lastSuccessAt > 0 && System.currentTimeMillis() - lastSuccessAt < interval) {
            return;
        }
        pending = false;
        syncOnce(allowEmptyOnce);
    }

    void syncOnce(boolean allowEmpty) {
        syncOnce(allowEmpty, 0);
    }

    private void syncOnce(boolean allowEmpty, int retryCount) {
        synchronized (this) {
            ConfigHolder.GatewayConfig cfg = configHolder.getConfig();
            LocalInventoryDocument doc = store.read();
            if (doc.getDevices().isEmpty() && !allowEmpty) {
                pending = false;
                nextAttemptAt = System.currentTimeMillis() + intervalMs();
                runtimeState.markInventorySyncBlocked("空清单已阻止，需在本地页面确认“清空全部”");
                log.warn("空设备清单已阻止，不向云端发送");
                return;
            }
            String body = buildBody(cfg, doc, allowEmpty);
            CloudClient.SyncResponse resp = cloudClient.syncDevices(body);
            try {
                JsonNode root = mapper.readTree(resp.body());
                int code = root == null ? 0 : root.path("code").asInt(0);
                if (resp.httpStatus() == 200 && code == 200) {
                    failCount = 0;
                    nextAttemptAt = 0L;
                    lastSuccessAt = System.currentTimeMillis();
                    allowEmptyOnce = false;
                    runtimeState.markInventorySyncSuccess(doc.getRevision(), doc.getDevices().size());
                    log.info("设备清单同步成功: revision={}, devices={}", doc.getRevision(), doc.getDevices().size());
                    return;
                }
                if (code == 409) {
                    if (retryCount >= 1) {
                        fail(resp.httpStatus(), resp.body());
                        return;
                    }
                    long current = root.path("data").path("currentRevision").asLong(0);
                    store.bumpRevisionTo(current + 1);
                    log.warn("设备清单版本落后，本地 revision 提升到 {} 后重试", current + 1);
                    syncOnce(allowEmpty, 1);
                    return;
                }
                fail(resp.httpStatus(), resp.body());
            } catch (Exception e) {
                fail(resp.httpStatus(), resp.body());
            }
        }
    }

    private String buildBody(ConfigHolder.GatewayConfig cfg, LocalInventoryDocument doc, boolean allowEmpty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gatewayCode", cfg.getGatewayCode());
        body.put("revision", doc.getRevision());
        body.put("updatedAt", doc.getUpdatedAt());
        body.put("full", true);
        body.put("allowEmpty", allowEmpty);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (MappingDevice d : doc.getDevices()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceName", d.getDeviceName());
            item.put("deviceIp", d.getDeviceIp());
            item.put("deviceType", d.getDeviceType());
            item.put("location", d.getLocation());
            item.put("brand", d.getBrand());
            item.put("model", d.getModel());
            item.put("collectType", "ping");
            item.put("collectPort", 0);
            item.put("warrantyExpire", d.getWarrantyExpire());
            devices.add(item);
        }
        body.put("devices", devices);
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("构建设备清单同步报文失败", e);
        }
    }

    private void fail(int httpStatus, String body) {
        failCount++;
        int index = Math.min(failCount - 1, BACKOFF_SECONDS.length - 1);
        long next = System.currentTimeMillis() + BACKOFF_SECONDS[index] * 1000L;
        nextAttemptAt = next;
        runtimeState.markInventorySyncFailure(
                "HTTP " + httpStatus + " " + (body == null ? "" : body),
                LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[index]).format(TS));
        log.warn("设备清单同步失败，{} 秒后重试: HTTP {} {}", BACKOFF_SECONDS[index], httpStatus, body);
    }

    private long intervalMs() {
        ConfigHolder.Local local = configHolder.getConfig().getLocal();
        int seconds = local == null ? 300 : local.getInventorySyncInterval();
        return Math.max(15, seconds) * 1000L;
    }
}
