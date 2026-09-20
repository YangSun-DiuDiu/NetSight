package com.netsight.gateway.connector;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 心跳上报：每 30s POST /edge/report/heartbeat，
 * 云端据此判定网关在线（5 分钟无心跳标红）。
 */
@Slf4j
@Component
public class HeartbeatService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigHolder configHolder;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;

    public HeartbeatService(ConfigHolder configHolder, CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        ConfigHolder.GatewayConfig cfg = configHolder.getConfig();
        if (cfg == null) {
            return;
        }
        String body = "{\"gatewayCode\":\"" + cfg.getGatewayCode() + "\","
                + "\"status\":\"online\","
                + "\"time\":\"" + LocalDateTime.now().format(FMT) + "\"}";
        boolean ok = cloudClient.sendHeartbeat(body);
        if (ok) {
            runtimeState.markHeartbeat();
            runtimeState.markCloud(true, runtimeState.getCurrentLink());
            runtimeState.setLastError("-");
        } else {
            runtimeState.markCloud(false, runtimeState.getCurrentLink());
            runtimeState.setLastError("云端不可达（心跳失败）");
        }
        log.debug("心跳上报: {} -> {}", ok ? "成功" : "失败", cfg.getCloud().getBaseUrl());
    }
}
