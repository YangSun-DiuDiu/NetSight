package com.netsight.gateway.alerter;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.queue.CacheQueue;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 告警转发器：接收 AlertManager Webhook 后立即转投云端 /alert/push，
 * 失败交由 CacheQueue 落盘补传（不丢事件）。
 */
@Slf4j
@Component
public class AlertForwarder {

    private final CloudClient cloudClient;
    private final CacheQueue cacheQueue;
    private final RuntimeState runtimeState;

    public AlertForwarder(CloudClient cloudClient, CacheQueue cacheQueue, RuntimeState runtimeState) {
        this.cloudClient = cloudClient;
        this.cacheQueue = cacheQueue;
        this.runtimeState = runtimeState;
    }

    /**
     * 转发一条告警。
     *
     * @param rawBody AlertManager webhook 原始 JSON（不解析，原样转投，云端 AlertPushService 双重兼容）
     * @return true=云端已确认；false=已缓存待补传
     */
    public boolean forward(String rawBody) {
        boolean ok = cloudClient.forwardAlert(rawBody);
        runtimeState.markAlert(ok);
        if (ok) {
            log.debug("告警转投云端成功");
            return true;
        }
        log.warn("告警转投失败，写入缓存队列");
        cacheQueue.enqueue("alert", rawBody);
        runtimeState.setLastError("告警转投失败，已缓存（队列 " + runtimeState.getCacheQueueSize() + "）");
        return false;
    }
}
