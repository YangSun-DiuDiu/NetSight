package com.netsight.modules.system.controller;

import com.netsight.common.core.R;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.config.PushWebSocketHandler;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.service.DeviceService;
import com.netsight.modules.system.service.EdgeGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 边缘网关状态上报接口（SNMP 指标接收，云端接入点）
 * 边缘网关本地 Prometheus/SNMP Exporter 采集后，将设备状态快照上报云端：
 * 1. 设备状态快照（device_up / device_line_abnormal）→ 更新设备三色状态
 * 2. 心跳（每次上报即心跳）→ 更新网关在线状态
 * 鉴权：请求头 X-Gateway-Token（网关接入 Token，非用户 JWT）
 */
@Slf4j
@RestController
@RequestMapping("/edge/report")
@RequiredArgsConstructor
public class GatewayReportController {

    private final EdgeGatewayService edgeGatewayService;
    private final DeviceService deviceService;

    /**
     * 设备状态批量上报
     * POST /edge/report/status
     * Header: X-Gateway-Token: <网关Token>
     * Body: {"devices":[{"deviceCode":"DEVxxx","up":1,"lineAbnormal":0}]}
     */
    @PostMapping("/status")
    public R<Void> reportStatus(@RequestHeader("X-Gateway-Token") String token,
                                @RequestBody(required = false) Map<String, Object> body) {
        // 1. Token 鉴权（禁用网关拒绝）
        EdgeGateway gateway = edgeGatewayService.getByToken(token);
        if (gateway == null) {
            throw new ServiceException(ResultCode.GATEWAY_TOKEN_INVALID);
        }
        // 2. 刷新网关心跳与在线状态
        String ip = body == null ? null : (String) body.get("ipAddress");
        edgeGatewayService.heartbeat(gateway, ip);
        // 3. 批量更新设备状态
        if (body != null && body.get("devices") instanceof List<?> rawList) {
            List<Device> reports = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> m) {
                    Device d = new Device();
                    d.setDeviceCode(String.valueOf(m.get("deviceCode")));
                    d.setStatus(toInt(m.get("up")));
                    d.setLineStatus(toInt(m.get("lineAbnormal")));
                    reports.add(d);
                }
            }
            deviceService.updateStatusBatch(reports, gateway.getId());
        }
        log.info("网关[{}]上报状态快照，设备数: {}", gateway.getGatewayName(),
                body == null ? 0 : (body.get("devices") instanceof List<?> l ? l.size() : 0));
        // 实时推送：设备状态变更广播（前端拓扑/列表实时刷新）
        PushWebSocketHandler.broadcast("device-status", Map.of(
                "gatewayName", gateway.getGatewayName(),
                "gatewayCode", gateway.getGatewayCode(),
                "count", body == null ? 0 : (body.get("devices") instanceof List<?> l ? l.size() : 0)));
        return R.ok();
    }

    /**
     * 网关心跳（不带设备数据，仅保活）
     * POST /edge/report/heartbeat
     */
    @PostMapping("/heartbeat")
    public R<Void> heartbeat(@RequestHeader("X-Gateway-Token") String token) {
        EdgeGateway gateway = edgeGatewayService.getByToken(token);
        if (gateway == null) {
            throw new ServiceException(ResultCode.GATEWAY_TOKEN_INVALID);
        }
        edgeGatewayService.heartbeat(gateway, null);
        return R.ok();
    }

    /**
     * 安全转 int：兼容布尔（nams-agent 上报 up/lineAbnormal 为 true/false）
     * 与数字（Python 模拟器/旧采集器上报 1/0）。
     * null/异常返回 0（视为离线，避免异常导致全置 0 误判）。
     */
    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            // 字符串 "1"/"true" 兜底
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
