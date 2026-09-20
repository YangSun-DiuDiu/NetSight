package com.netsight.modules.alert.channel;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书群机器人通道（channelType = feishu）
 * config_json: {webhookUrl, secret(可选签名校验)}
 */
@Slf4j
@Component
public class FeishuChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "feishu"; }

    @Override
    public String getChannelName() { return "飞书群机器人"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null || !StringUtils.hasText((String) cfg.get("webhookUrl"))) {
                return fail("未配置飞书 webhookUrl", start);
            }
            String webhookUrl = (String) cfg.get("webhookUrl");

            Map<String, Object> body = new HashMap<>();
            body.put("msg_type", "text");
            Map<String, Object> content = new HashMap<>();
            content.put("text", (request.getContent() == null ? "" : request.getContent()));
            body.put("content", content);

            String resp = HttpUtil.post(webhookUrl, JSONUtil.toJsonStr(body), 5000);
            JSONObject json = JSONUtil.parseObj(resp);
            int code = json.getInt("code", -1);
            if (code != 0) {
                return fail("飞书返回: " + json.getStr("msg", resp), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId("FS-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("飞书发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
