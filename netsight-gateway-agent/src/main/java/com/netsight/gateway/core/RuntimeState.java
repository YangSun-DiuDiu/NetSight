package com.netsight.gateway.core;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关运行态（内存权威状态）：云端连接、上报统计、设备状态缓存、补传队列长度。
 * 本地管理页面（状态总览）与上报调度均读取本类。
 */
@Slf4j
@Component
public class RuntimeState {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 云端连通性（由 LinkManager 周期探测刷新） */
    private final AtomicBoolean cloudConnected = new AtomicBoolean(false);
    /** 当前生效链路 */
    private volatile String currentLink = "unknown";
    /** 最近心跳时间 */
    private volatile String lastHeartbeatTime = "-";
    /** 最近状态快照时间 */
    private volatile String lastSnapshotTime = "-";
    /** 快照累计次数 / 失败次数 */
    private final AtomicLong snapshotCount = new AtomicLong(0);
    private final AtomicLong snapshotFailCount = new AtomicLong(0);
    /** 心跳累计次数 */
    private final AtomicLong heartbeatCount = new AtomicLong(0);
    /** 告警累计转发次数 / 失败次数 */
    private final AtomicLong alertForwardCount = new AtomicLong(0);
    private final AtomicLong alertFailCount = new AtomicLong(0);
    /** 补传队列长度（由 CacheQueue 维护） */
    private final AtomicLong cacheQueueSize = new AtomicLong(0);
    /** 最后一次错误信息 */
    private volatile String lastError = "-";

    /** 设备状态缓存：deviceCode -> DeviceState（快照聚合后写入，供管理页展示） */
    private final Map<String, DeviceState> deviceStates = new ConcurrentHashMap<>();

    /** 最近一次清单同步结果 */
    private volatile String lastSyncResult = "-";

    @Data
    public static class DeviceState {
        private String deviceCode;
        private String deviceName;
        private String deviceIp;
        private String deviceType;
        private String location;
        private Boolean up;
        private Boolean lineAbnormal;
        private String collectedAt;
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }

    public void markHeartbeat() {
        heartbeatCount.incrementAndGet();
        lastHeartbeatTime = now();
    }

    public void markSnapshot(boolean success) {
        snapshotCount.incrementAndGet();
        lastSnapshotTime = now();
        if (!success) {
            snapshotFailCount.incrementAndGet();
        }
    }

    public void markAlert(boolean success) {
        alertForwardCount.incrementAndGet();
        if (!success) {
            alertFailCount.incrementAndGet();
        }
    }

    public void markCloud(boolean connected, String link) {
        cloudConnected.set(connected);
        if (connected) {
            currentLink = link;
        }
    }

    public void setLastError(String msg) {
        this.lastError = msg;
    }

    public void upsertDevice(DeviceState state) {
        deviceStates.put(state.getDeviceCode(), state);
    }

    public void setCacheQueueSize(long size) {
        cacheQueueSize.set(size);
    }

    public void setLastSyncResult(String result) {
        this.lastSyncResult = result;
    }

    public boolean isCloudConnected() {
        return cloudConnected.get();
    }

    public String getCurrentLink() {
        return currentLink;
    }

    public String getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public String getLastSnapshotTime() {
        return lastSnapshotTime;
    }

    public long getSnapshotCount() {
        return snapshotCount.get();
    }

    public long getSnapshotFailCount() {
        return snapshotFailCount.get();
    }

    public long getHeartbeatCount() {
        return heartbeatCount.get();
    }

    public long getAlertForwardCount() {
        return alertForwardCount.get();
    }

    public long getAlertFailCount() {
        return alertFailCount.get();
    }

    public long getCacheQueueSize() {
        return cacheQueueSize.get();
    }

    public String getLastError() {
        return lastError;
    }

    public Map<String, DeviceState> getDeviceStates() {
        return deviceStates;
    }

    public String getLastSyncResult() {
        return lastSyncResult;
    }

    public long onlineCount() {
        return deviceStates.values().stream().filter(d -> Boolean.TRUE.equals(d.getUp())).count();
    }

    public long offlineCount() {
        return deviceStates.values().stream().filter(d -> Boolean.FALSE.equals(d.getUp())).count();
    }

    public long lineAbnormalCount() {
        return deviceStates.values().stream()
                .filter(d -> Boolean.TRUE.equals(d.getUp()) && Boolean.TRUE.equals(d.getLineAbnormal()))
                .count();
    }
}
