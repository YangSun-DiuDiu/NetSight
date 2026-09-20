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
 * 企业微信应用消息通道（channelType = wechat_app）
 * config_json: {corpid, corpsecret, agentid, touser(可选,@all)}
 * 先 gettoken，再 message/send（text 类型）
 */
@Slf4j
@Component
public class WechatAppChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "wechat_app"; }

    @Override
    public String getChannelName() { return "企业微信应用消息"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null) return fail("未配置企业微信应用参数", start);
            String corpid = (String) cfg.get("corpid");
            String corpsecret = (String) cfg.get("corpsecret");
            String agentid = String.valueOf(cfg.getOrDefault("agentid", "0"));
            String touser = (String) cfg.getOrDefault("touser", "@all");

            if (!StringUtils.hasText(corpid) || !StringUtils.hasText(corpsecret)) {
                return fail("corpid/corpsecret 不完整", start);
            }

            // 1. gettoken
            String tokenUrl = String.format(
                    "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s",
                    corpid, corpsecret);
            String tokenResp = HttpUtil.get(tokenUrl, 5000);
            JSONObject tokenJson = JSONUtil.parseObj(tokenResp);
            String accessToken = tokenJson.getStr("access_token");
            if (!StringUtils.hasText(accessToken)) {
                return fail("企业微信 gettoken 失败: " + tokenJson.getStr("errmsg"), start);
            }

            // 2. message/send
            String sendUrl = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + accessToken;
            Map<String, Object> body = new HashMap<>();
            body.put("touser", touser);
            body.put("msgtype", "text");
            Map<String, Object> text = new HashMap<>();
            text.put("content", request.getContent() == null ? "" : request.getContent());
            body.put("text", text);
            body.put("agentid", Integer.parseInt(agentid));

            String resp = HttpUtil.post(sendUrl, JSONUtil.toJsonStr(body), 5000);
            JSONObject json = JSONUtil.parseObj(resp);
            int errcode = json.getInt("errcode", -1);
            if (errcode != 0) {
                return fail("企业微信应用消息返回: " + json.getStr("errmsg"), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId("WXAPP-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("企业微信应用消息发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
