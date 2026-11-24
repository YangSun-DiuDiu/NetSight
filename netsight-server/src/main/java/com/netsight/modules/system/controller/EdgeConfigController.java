package com.netsight.modules.system.controller;

import com.netsight.common.core.R;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.exception.StaleRevisionException;
import com.netsight.modules.system.dto.DeviceInventorySyncRequest;
import com.netsight.modules.system.dto.DeviceInventorySyncResult;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.service.DeviceInventorySyncService;
import com.netsight.modules.system.service.DeviceService;
import com.netsight.modules.system.service.EdgeGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 边缘网关配置下发接口（/edge/config/**）
 * 独立于 /edge/report（状态上报），避免路径冲突。
 * <p>
 * 核心能力：采集清单（mapping.json 数据源）云端下发。
 * 设备在云端录入/修改/删除后，网关侧 nams-agent 每 60s 轮询本接口，
 * 自动生成 Prometheus file_sd targets（设备增删改零重启生效）。
 * 鉴权：请求头 X-Gateway-Token（网关接入 Token，非用户 JWT）。
 */
@Slf4j
@RestController
@RequestMapping("/edge/config")
@RequiredArgsConstructor
public class EdgeConfigController {

    private final EdgeGatewayService edgeGatewayService;
    private final DeviceService deviceService;
    private final DeviceInventorySyncService deviceInventorySyncService;

    /**
     * 云端采集清单下发（mapping.json 数据源）
     * GET /edge/config/mapping
     * Header: X-Gateway-Token: <网关Token>
     * <p>
     * 返回结构对齐 nams-agent MappingSync 解析协议：
     * {"version":"v1.1.7","devices":[
     *   {deviceCode, deviceName, deviceIp, deviceType, location,
     *    collectType, collectPort,
     *    labels:{tenant_id, gateway_code, gateway_name}}
     * ]}
     * 网关侧据此生成 Prometheus file_sd targets；labels 透传业务标签（租户/网关）。
     */
    @GetMapping("/mapping")
    public R<Map<String, Object>> mapping(@RequestHeader("X-Gateway-Token") String token) {
        EdgeGateway gateway = edgeGatewayService.getByToken(token);
        if (gateway == null) {
            throw new ServiceException(ResultCode.GATEWAY_TOKEN_INVALID);
        }
        List<Device> devices = deviceService.listByGateway(gateway.getId());
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Device d : devices) {
            Map<String, Object> item = new HashMap<>();
            item.put("deviceCode", d.getDeviceCode());
            item.put("deviceName", d.getDeviceName());
            item.put("deviceIp", d.getIpAddress());
            item.put("deviceType", d.getDeviceType());
            item.put("location", d.getLocation());
            item.put("collectType", d.getCollectType() == null ? "ping" : d.getCollectType());
            item.put("collectPort", d.getCollectPort() == null ? 0 : d.getCollectPort());
            Map<String, String> labels = new HashMap<>();
            labels.put("tenant_id", String.valueOf(gateway.getTenantId()));
            labels.put("gateway_code", gateway.getGatewayCode());
            labels.put("gateway_name", gateway.getGatewayName() == null ? "" : gateway.getGatewayName());
            item.put("labels", labels);
            arr.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("version", "v1.1.7");
        result.put("devices", arr);
        log.info("网关[{}]拉取采集清单，下发设备 {} 台", gateway.getGatewayName(), arr.size());
        return R.ok(result);
    }

    /**
     * 网关全量设备清单同步（网关 -> 云端单向）
     * POST /edge/config/devices/sync
     * Header: X-Gateway-Token: <网关Token>
     */
    @PostMapping("/devices/sync")
    public R<Map<String, Object>> syncDevices(@RequestHeader("X-Gateway-Token") String token,
                                              @RequestBody DeviceInventorySyncRequest req) {
        EdgeGateway gateway = edgeGatewayService.getByToken(token);
        if (gateway == null) {
            throw new ServiceException(ResultCode.GATEWAY_TOKEN_INVALID);
        }
        try {
            DeviceInventorySyncResult result = deviceInventorySyncService.sync(gateway, req);
            Map<String, Object> data = new HashMap<>();
            data.put("revision", result.getRevision());
            data.put("created", result.getCreated());
            data.put("updated", result.getUpdated());
            data.put("disabled", result.getDisabled());
            data.put("unchanged", result.getUnchanged());
            data.put("syncTime", result.getSyncTime());
            return R.ok(data);
        } catch (StaleRevisionException e) {
            R<Map<String, Object>> r = R.fail(409, "STALE_REVISION");
            r.setData(Map.of("currentRevision", e.getCurrentRevision()));
            return r;
        }
    }
}
