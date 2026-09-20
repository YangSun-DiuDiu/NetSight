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
 * 企业微信群机器人通道（channelType = wechat_work）
 * config_json: {webhookUrl, mentionedList(逗号分隔手机号,可选)}
 */
@Slf4j
@Component
public class WechatWorkChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "wechat_work"; }

    @Override
    public String getChannelName() { return "企业微信群机器人"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null || !StringUtils.hasText((String) cfg.get("webhookUrl"))) {
                return fail("未配置企业微信 webhookUrl", start);
            }
            String webhookUrl = (String) cfg.get("webhookUrl");

            Map<String, Object> body = new HashMap<>();
            body.put("msgtype", "markdown");
            Map<String, Object> markdown = new HashMap<>();
            markdown.put("content", (request.getContent() == null ? "" : request.getContent()));
            body.put("markdown", markdown);

            String mentioned = (String) cfg.get("mentionedList");
            if (StringUtils.hasText(mentioned)) {
                body.put("mentioned_mobile_list", mentioned.split(","));
            }

            String resp = HttpUtil.post(webhookUrl, JSONUtil.toJsonStr(body), 5000);
            JSONObject json = JSONUtil.parseObj(resp);
            int errcode = json.getInt("errcode", -1);
            if (errcode != 0) {
                return fail("企业微信返回: " + json.getStr("errmsg", resp), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId("WW-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("企业微信发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
