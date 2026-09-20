package com.netsight.modules.alert.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.entity.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AlertManager Webhook 接入服务
 * 解析 AlertManager v4 webhook 报文（groups[].alerts[] 结构）与方案标准事件格式，
 * 转换为统一 EventRecord 后送入事件中心。
 *
 * 边缘网关上的 AlertManager 通过 webhook 推送：
 *   POST {云端地址}/alert/push
 *   请求头 X-Netsight-Webhook-Token: {云端配置的接入密钥}
 *   请求头 X-Netsight-Tenant-Id: {事件所属租户}
 *
 * 兼容两种报文：
 * 1. AlertManager v4 原生：{"status":"firing","commonLabels":{...},"alerts":[{labels,annotations,generatorURL,...}]}
 * 2. 方案标准格式：{"event_type":"device_offline","biz_id":"...","labels":{severity,device_ip,...},"content_vars":{...}}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertPushService {

    private final EventCenterService eventCenterService;
    private final ObjectMapper objectMapper;

    /**
     * alertname → event_type 映射（与 Prometheus 告警规则联动）
     */
    private static final Map<String, String> ALERTNAME_MAP = Map.of(
            "网络设备离线故障", "device_offline",
            "设备离线", "device_offline",
            "设备外线状态异常", "device_line_abnormal",
            "外线异常", "device_line_abnormal",
            "外线链路异常", "device_line_abnormal",
            "故障恢复", "device_recovered",
            "告警恢复", "device_recovered"
    );

    /**
     * 接收 webhook 报文并转换入库
     *
     * @param body     报文 JSON（Map）
     * @param tenantId 事件归属租户（网关配置下发，非登录态）
     * @return 产生的事件ID列表
     */
    public List<Long> push(Map<String, Object> body, Long tenantId) {
        List<EventRecord> events = parseToEvents(body, tenantId);
        List<Long> eventIds = new ArrayList<>();
        for (EventRecord event : events) {
            eventIds.add(eventCenterService.receiveEvent(event));
        }
        log.info("Webhook 接入完成：报文{}条，产生事件{}个", events.size(), eventIds.size());
        return eventIds;
    }

    /**
     * 报文解析：兼容 AlertManager v4 与标准事件格式
     */
    @SuppressWarnings("unchecked")
    private List<EventRecord> parseToEvents(Map<String, Object> body, Long tenantId) {
        List<EventRecord> events = new ArrayList<>();
        try {
            // 分支1：AlertManager v4 原生报文（alerts 数组）
            if (body.containsKey("alerts") && body.get("alerts") instanceof List<?> alertsList) {
                String commonSeverity = str(body.get("commonLabels"), "severity", null);
                Map<String, Object> commonLabels = obj(body.get("commonLabels"));
                for (Object item : alertsList) {
                    Map<String, Object> alert = (Map<String, Object>) item;
                    events.add(buildFromAlertManager(alert, commonSeverity, commonLabels, tenantId));
                }
                return events;
            }
            // 分支2：方案标准事件格式（event_type 字段）
            if (body.containsKey("event_type")) {
                events.add(buildFromStandard(body, tenantId));
                return events;
            }
            throw new ServiceException("无法识别的 webhook 报文格式");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Webhook 报文解析失败: {}", e.getMessage(), e);
            throw new ServiceException("Webhook 报文解析失败: " + e.getMessage());
        }
    }

    /**
     * AlertManager v4 单条 alert → EventRecord
     */
    private EventRecord buildFromAlertManager(Map<String, Object> alert, String commonSeverity,
                                              Map<String, Object> commonLabels, Long tenantId) {
        Map<String, Object> labels = obj(alert.get("labels"));
        Map<String, Object> annotations = obj(alert.get("annotations"));
        String status = str(alert, "status", "firing");
        String alertname = firstNonEmpty(
                str(labels, "alertname", null),
                str(commonLabels, "alertname", null));
        String severity = "resolved".equals(status)
                ? "resolved"
                : firstNonEmpty(str(labels, "severity", null), commonSeverity, "warning");
        String deviceIp = firstNonEmpty(str(labels, "device_ip", null), str(commonLabels, "device_ip", null));
        String deviceName = firstNonEmpty(str(labels, "device_name", null), str(commonLabels, "device_name", null));
        String deviceType = firstNonEmpty(str(labels, "device_type", null), str(commonLabels, "device_type", null));
        String location = firstNonEmpty(str(labels, "device_location", null), str(commonLabels, "device_location", null));
        String summary = firstNonEmpty(str(annotations, "summary", null), str(alert, "summary", null));
        String description = firstNonEmpty(str(annotations, "description", null), str(alert, "description", null));
        String generatorUrl = str(alert, "generatorURL", null);

        EventRecord event = new EventRecord();
        event.setTenantId(tenantId);
        event.setEventType(mapEventType(alertname, status));
        event.setEventSource("alertmanager");
        event.setSeverity(severity);
        event.setBizId(genBizId(alertname, deviceIp, generatorUrl, status));
        event.setDeviceName(deviceName);
        event.setDeviceIp(deviceIp);
        event.setDeviceType(deviceType);
        event.setLocation(location);
        event.setLabelsJson(toJson(labels));
        event.setContentVarsJson(toJson(buildContentVars(labels, annotations)));
        event.setContent(firstNonEmpty(description, summary, ""));
        return event;
    }

    /**
     * 方案标准事件格式 → EventRecord
     */
    private EventRecord buildFromStandard(Map<String, Object> body, Long tenantId) {
        Map<String, Object> labels = obj(body.get("labels"));
        Map<String, Object> contentVars = obj(body.get("content_vars"));
        Map<String, Object> annotations = obj(body.get("annotations"));
        String status = str(body, "status", "firing");

        EventRecord event = new EventRecord();
        event.setTenantId(tenantId);
        event.setEventType(str(body, "event_type", "unknown_event"));
        event.setEventSource(str(body, "event_source", "alertmanager"));
        event.setSeverity("resolved".equals(status)
                ? "resolved"
                : firstNonEmpty(str(body, "severity", null), str(labels, "severity", null), "warning"));
        event.setBizId(str(body, "biz_id", null));
        event.setDeviceName(firstNonEmpty(str(labels, "device_name", null), str(contentVars, "device_name", null)));
        event.setDeviceIp(firstNonEmpty(str(labels, "device_ip", null), str(contentVars, "device_ip", null)));
        event.setDeviceType(firstNonEmpty(str(labels, "device_type", null), str(contentVars, "device_type", null)));
        event.setLocation(firstNonEmpty(str(labels, "device_location", null), str(contentVars, "location", null)));
        event.setLabelsJson(toJson(labels));
        event.setContentVarsJson(toJson(contentVars));
        event.setContent(firstNonEmpty(str(body, "content", null), str(annotations, "description", null), ""));
        return event;
    }

    /**
     * alertname → event_type 映射（未知告警保留 alertname 原文）
     */
    private String mapEventType(String alertname, String status) {
        if ("resolved".equals(status)) {
            return "device_recovered";
        }
        if (alertname != null && ALERTNAME_MAP.containsKey(alertname)) {
            return ALERTNAME_MAP.get(alertname);
        }
        return alertname == null ? "unknown_event" : alertname;
    }

    /**
     * 去重业务ID：alertname+ip+generatorURL 指纹
     */
    private String genBizId(String alertname, String deviceIp, String generatorUrl, String status) {
        String fingerprint = (alertname == null ? "" : alertname)
                + (deviceIp == null ? "" : deviceIp)
                + (generatorUrl == null ? "" : generatorUrl);
        int hash = fingerprint.hashCode();
        return (status + "_" + Math.abs(hash));
    }

    private Map<String, Object> buildContentVars(Map<String, Object> labels, Map<String, Object> annotations) {
        Map<String, Object> vars = new java.util.HashMap<>(labels);
        vars.putAll(annotations);
        return vars;
    }

    // ==================== JSON 工具 ====================

    private String str(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || map.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(map.get(key));
    }

    private String str(Object map, String key, String defaultValue) {
        if (!(map instanceof Map<?, ?> m)) {
            return defaultValue;
        }
        Object v = m.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> obj(Object map) {
        return map instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
