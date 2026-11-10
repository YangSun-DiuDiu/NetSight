package com.netsight.modules.alert.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.netsight.modules.alert.service.NotificationTemplateService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信公众号模板描述符解析 / 渲染 / 字段截断。
 *
 * content 示例：
 * {"templateId":"...","url":"...","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"}]}
 * content 为空或非 JSON 时回落现有硬编码 5 字段，保证 115 历史模板不回归。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatTemplatePayload {

    private static final Map<String, String> STATE = Map.ofEntries(
            Map.entry("device_offline", "设备离线"),
            Map.entry("device_line_abnormal", "外线异常"),
            Map.entry("device_recovered", "已恢复"),
            Map.entry("device_cpu_high", "CPU过高"),
            Map.entry("device_mem_high", "内存过高"),
            Map.entry("order_dispatch", "待接单"),
            Map.entry("stock_low", "库存不足"),
            Map.entry("manual_notify", "通知")
    );

    private static final Map<String, String> TITLE = Map.ofEntries(
            Map.entry("device_offline", "设备离线告警"),
            Map.entry("device_line_abnormal", "链路异常告警"),
            Map.entry("device_recovered", "故障恢复通知"),
            Map.entry("device_cpu_high", "CPU使用率告警"),
            Map.entry("device_mem_high", "内存使用率告警"),
            Map.entry("order_dispatch", "工单派发"),
            Map.entry("stock_low", "备件库存预警"),
            Map.entry("manual_notify", "系统通知")
    );

    private final ObjectMapper objectMapper;

    public Rendered render(SendRequest request, String fallbackTemplateId) {
        Map<String, Object> vars = request.getContentVars() == null
                ? new HashMap<>() : new HashMap<>(request.getContentVars());
        enrich(vars, request);
        Descriptor descriptor = parse(request.getContent());
        String templateId = descriptor != null && StringUtils.hasText(descriptor.getTemplateId())
                ? descriptor.getTemplateId() : fallbackTemplateId;
        List<Field> fields = descriptor != null && descriptor.getFields() != null
                && !descriptor.getFields().isEmpty()
                ? descriptor.getFields() : defaultFields();
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        for (Field f : fields) {
            if (f == null || !StringUtils.hasText(f.getKey())) {
                continue;
            }
            String value = NotificationTemplateService.render(f.getValue(), vars);
            if (!StringUtils.hasText(value)) {
                value = f.getDefaultValue() == null ? "" : f.getDefaultValue();
            }
            data.put(f.getKey(), Map.of("value", cut(value, limit(f.getKey()))));
        }
        String url = descriptor != null && StringUtils.hasText(descriptor.getUrl())
                ? descriptor.getUrl() : null;
        String summary = "模板=" + templateId + "；"
                + data.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().get("value"))
                    .reduce((a, b) -> a + "，" + b).orElse("");
        return new Rendered(templateId, url, data, summary);
    }

    public String resolveTemplateId(String content, String fallback) {
        Descriptor d = parse(content);
        return d != null && StringUtils.hasText(d.getTemplateId()) ? d.getTemplateId() : fallback;
    }

    private Descriptor parse(String content) {
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) {
            return null;
        }
        try {
            return objectMapper.readValue(content, Descriptor.class);
        } catch (Exception e) {
            log.warn("微信模板描述符解析失败，回落默认 5 字段: {}", e.getMessage());
            return null;
        }
    }

    private List<Field> defaultFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("thing2", "{{device_name}}", "未知设备"));
        fields.add(new Field("phrase9", "{{event_state}}", "告警"));
        fields.add(new Field("character_string11", "{{device_no}}", "-"));
        fields.add(new Field("thing17", "{{location}}", "未登记位置"));
        fields.add(new Field("phrase20", "{{severity_text}}", "一般"));
        return fields;
    }

    private void enrich(Map<String, Object> vars, SendRequest req) {
        String eventType = first(vars, "event_type", "eventType");
        vars.putIfAbsent("event_state", STATE.getOrDefault(eventType, "告警"));
        vars.putIfAbsent("event_title", TITLE.getOrDefault(eventType, "系统通知"));
        vars.putIfAbsent("severity_text", severityText(first(vars, "severity")));
        vars.putIfAbsent("device_no", firstNonEmpty(
                first(vars, "device_code", "deviceCode"),
                first(vars, "device_ip", "deviceIp"),
                req.getBizId()));
        vars.putIfAbsent("biz_id", req.getBizId() == null ? "" : req.getBizId());
        vars.putIfAbsent("time", LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if (!vars.containsKey("content") && req.getContent() != null) {
            vars.put("content", req.getContent());
        }
    }

    static String severityText(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "一般";
        }
        return switch (severity.toLowerCase()) {
            case "critical", "fatal", "emergency" -> "严重";
            case "warning", "warn", "major" -> "重要";
            case "resolved", "ok", "recovered" -> "已恢复";
            default -> "一般";
        };
    }

    static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        int cp = t.codePointCount(0, t.length());
        if (cp <= max) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, max));
    }

    static int limit(String key) {
        if (key == null) {
            return 20;
        }
        if (key.startsWith("thing") || key.startsWith("const")
                || key.startsWith("first") || key.startsWith("remark")) {
            return 20;
        }
        if (key.startsWith("phrase") || key.startsWith("letter")
                || key.startsWith("symbol")) {
            return 5;
        }
        if (key.startsWith("character_string") || key.startsWith("number")) {
            return 32;
        }
        if (key.startsWith("amount") || key.startsWith("date")) {
            return 10;
        }
        if (key.startsWith("time")) {
            return 20;
        }
        return 20;
    }

    private static String first(Map<String, Object> vars, String... keys) {
        for (String k : keys) {
            Object v = vars.get(k);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return "";
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Descriptor {
        private String templateId;
        private String url;
        private List<Field> fields;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Field {
        private String key;
        private String value;

        @JsonProperty("default")
        private String defaultValue;

        public Field() {
        }

        public Field(String key, String value, String defaultValue) {
            this.key = key;
            this.value = value;
            this.defaultValue = defaultValue;
        }
    }

    @Builder
    public record Rendered(String templateId, String url,
                           Map<String, Map<String, String>> data, String summary) {
    }
}
