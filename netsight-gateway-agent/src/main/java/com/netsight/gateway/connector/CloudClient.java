package com.netsight.gateway.connector;

import com.netsight.gateway.core.ConfigHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 上行客户端：封装与云端的 HTTP 交互（心跳 / 状态快照 / 告警转投 / 清单拉取）。
 * <p>
 * 统一请求头：X-Gateway-Token（网关凭证）+ X-Netsight-Tenant-Id（租户），
 * 与云端 GatewayReportController / AlertPushController 契约一致。
 * </p>
 */
@Slf4j
@Component
public class CloudClient {

    private final ConfigHolder configHolder;
    private final HttpClient http;

    public CloudClient(ConfigHolder configHolder) {
        this.configHolder = configHolder;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 心跳上报：POST /edge/report/heartbeat */
    public boolean sendHeartbeat(String body) {
        return post("/edge/report/heartbeat", body, true);
    }

    /** 状态快照：POST /edge/report/status */
    public boolean sendSnapshot(String body) {
        return post("/edge/report/status", body, true);
    }

    /** 告警转投：POST /alert/push（携带租户 Webhook Token） */
    public boolean forwardAlert(String body) {
        return post("/alert/push", body, false);
    }

    /** 拉取云端采集清单（GET /edge/config/mapping，云端 v1.1.7 待实现；骨架提供，失败返回 null 走本地文件） */
    public String fetchMapping() {
        String base = base();
        if (base == null) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/edge/config/mapping"))
                    .timeout(Duration.ofMillis(configHolder.getConfig().getCloud().getTimeoutMs()))
                    .header("X-Gateway-Token", configHolder.getConfig().getCloud().getGatewayToken())
                    .header("X-Netsight-Tenant-Id", String.valueOf(tenantId()))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            log.warn("拉取云端清单失败: HTTP {}", resp.statusCode());
        } catch (Exception e) {
            log.debug("拉取云端清单不可用（走本地文件）: {}", e.getMessage());
        }
        return null;
    }

    private boolean post(String path, String body, boolean gatewayAuth) {
        String base = base();
        if (base == null) {
            return false;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(Duration.ofMillis(configHolder.getConfig().getCloud().getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8");
            if (gatewayAuth) {
                b.header("X-Gateway-Token", configHolder.getConfig().getCloud().getGatewayToken());
                b.header("X-Netsight-Tenant-Id", String.valueOf(tenantId()));
            } else {
                b.header("X-Netsight-Webhook-Token", configHolder.getConfig().getCloud().getAlertWebhookToken());
                b.header("X-Netsight-Tenant-Id", String.valueOf(tenantId()));
            }
            HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return true;
            }
            log.warn("云端 {} 返回 HTTP {}: {}", path, resp.statusCode(), truncate(resp.body(), 200));
        } catch (Exception e) {
            log.warn("云端 {} 请求异常: {}", path, e.getMessage());
        }
        return false;
    }

    /** 构造多链路探测用请求头 map（LinkManager 复用） */
    public Map<String, String> gatewayHeaders() {
        return Map.of(
                "X-Gateway-Token", configHolder.getConfig().getCloud().getGatewayToken(),
                "X-Netsight-Tenant-Id", String.valueOf(tenantId())
        );
    }

    private String base() {
        ConfigHolder.Cloud cloud = configHolder.getConfig().getCloud();
        if (cloud == null || cloud.getBaseUrl() == null || cloud.getBaseUrl().isBlank()) {
            log.warn("云端 base_url 未配置");
            return null;
        }
        return cloud.getBaseUrl().replaceAll("/+$", "");
    }

    private long tenantId() {
        Long id = configHolder.getConfig().getCloud().getTenantId();
        return id == null ? 1L : id;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 探测云端连通性（GET /actuator/health，用于 LinkManager） */
    public boolean probeCloud(String baseUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/actuator/health"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 批量上报 JSON 构造辅助（供测试/脚本复用） */
    public static String buildSnapshotBody(String ipAddress, List<Map<String, Object>> devices) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ipAddress\":\"").append(ipAddress == null ? "" : ipAddress).append("\",\"devices\":[");
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{");
            Map<String, Object> d = devices.get(i);
            sb.append("\"deviceCode\":\"").append(d.get("deviceCode")).append('"');
            sb.append(",\"up\":").append(d.get("up"));
            sb.append(",\"lineAbnormal\":").append(d.getOrDefault("lineAbnormal", false));
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
