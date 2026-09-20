package com.netsight.gateway.wsclient;

import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 下行 WebSocket 客户端（骨架预留）。
 * <p>
 * 职责：与云端 /ws/gateway 保持长连接，接收下行指令
 * （重启/配置更新/立即采集/固件升级），心跳保活 + 断线自动重连。
 * </p>
 * <b>当前版本未实现实际连接</b>（云端 /ws/gateway 通道尚处预留态，网关当前走 HTTP 上报）；
 * 后续版本落地时实现：连接管理、消息路由（指令类型分发）、重连退避、超时回执。
 */
@Slf4j
@Component
public class CloudWsClient {

    private final ConfigHolder configHolder;
    private final RuntimeState runtimeState;

    public CloudWsClient(ConfigHolder configHolder, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.runtimeState = runtimeState;
    }

    /** 连接云端下行通道（预留） */
    public void connect() {
        log.info("CloudWsClient：下行 WebSocket 通道预留（云端 /ws/gateway 落地后启用）");
    }

    /** 发送回执（预留） */
    public void sendAck(String messageId) {
        log.debug("CloudWsClient：发送回执（预留） messageId={}", messageId);
    }

    /** 是否已连接（当前恒 false，预留） */
    public boolean isConnected() {
        return false;
    }
}
