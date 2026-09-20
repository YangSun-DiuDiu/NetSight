package com.netsight.modules.alert.channel;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 通用 Webhook 通道（channelType = webhook）
 * 自定义 HTTP POST，config_json: {url, method(默认POST), headers(JSON字符串,可选)}
 */
@Slf4j
@Component
public class WebhookChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "webhook"; }

    @Override
    public String getChannelName() { return "通用 Webhook"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null || !StringUtils.hasText((String) cfg.get("url"))) {
                return fail("未配置 webhook url", start);
            }
            String url = (String) cfg.get("url");
            String method = (String) cfg.getOrDefault("method", "POST");

            Map<String, Object> payload = new HashMap<>();
            payload.put("title", "NetSight 设备告警");
            payload.put("content", request.getContent());
            payload.put("bizId", request.getBizId());
            payload.put("tenantId", request.getTenantId());

            String resp;
            if ("GET".equalsIgnoreCase(method)) {
                resp = HttpUtil.get(url, 5000);
            } else {
                HttpRequest req = HttpRequest.post(url).body(JSONUtil.toJsonStr(payload)).timeout(5000);
                String headers = (String) cfg.get("headers");
                if (StringUtils.hasText(headers)) {
                    JSONObject h = JSONUtil.parseObj(headers);
                    h.forEach((k, v) -> req.header(k, String.valueOf(v)));
                }
                resp = req.execute().body();
            }
            log.info("[Webhook] 响应: {}", resp == null ? "" : resp.substring(0, Math.min(500, resp.length())));
            return SendResult.builder().success(true).thirdPartyMsgId("WH-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("Webhook 发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
