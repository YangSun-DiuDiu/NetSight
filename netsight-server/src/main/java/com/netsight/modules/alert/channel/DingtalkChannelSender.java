package com.netsight.modules.alert.channel;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉群机器人通道（channelType = dingtalk）
 * config_json: {webhookUrl, secret(可选加签), titlePrefix}
 */
@Slf4j
@Component
public class DingtalkChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "dingtalk"; }

    @Override
    public String getChannelName() { return "钉钉群机器人"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null || !StringUtils.hasText((String) cfg.get("webhookUrl"))) {
                return fail("未配置钉钉 webhookUrl", start);
            }
            String webhookUrl = (String) cfg.get("webhookUrl");
            String secret = (String) cfg.get("secret");
            String title = (String) cfg.getOrDefault("titlePrefix", "【运维告警】");
            if (!StringUtils.hasText(title)) title = "【运维告警】";

            // 加签
            if (StringUtils.hasText(secret)) {
                long ts = System.currentTimeMillis();
                String stringToSign = ts + "\n" + secret;
                HMac mac = new HMac(HmacAlgorithm.HmacSHA256, secret.getBytes(StandardCharsets.UTF_8));
                String sign = URLEncoder.encode(mac.digestBase64(stringToSign, false), StandardCharsets.UTF_8.name());
                String sep = webhookUrl.contains("?") ? "&" : "?";
                webhookUrl = webhookUrl + sep + "timestamp=" + ts + "&sign=" + sign;
            }

            Map<String, Object> body = new HashMap<>();
            body.put("msgtype", "markdown");
            Map<String, Object> markdown = new HashMap<>();
            markdown.put("title", title);
            markdown.put("text", title + "\n\n" + (request.getContent() == null ? "" : request.getContent()));
            body.put("markdown", markdown);

            String resp = HttpUtil.post(webhookUrl, JSONUtil.toJsonStr(body), 5000);
            JSONObject json = JSONUtil.parseObj(resp);
            int errcode = json.getInt("errcode", -1);
            if (errcode != 0) {
                return fail("钉钉返回: " + json.getStr("errmsg", resp), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId("DT-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("钉钉发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
