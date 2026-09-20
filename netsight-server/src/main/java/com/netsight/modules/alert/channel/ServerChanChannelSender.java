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
 * Server酱（ServerChan）通道（channelType = serverchan）
 * 个人微信推送，config_json: {sendkey}
 */
@Slf4j
@Component
public class ServerChanChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "serverchan"; }

    @Override
    public String getChannelName() { return "Server酱推送"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null || !StringUtils.hasText((String) cfg.get("sendkey"))) {
                return fail("未配置 Server酱 sendkey", start);
            }
            String sendkey = (String) cfg.get("sendkey");
            String url = "https://sctapi.ftqq.com/" + sendkey + ".send";

            Map<String, Object> form = new HashMap<>();
            form.put("title", "NetSight 设备告警");
            form.put("desp", request.getContent() == null ? "" : request.getContent());

            String resp = HttpUtil.post(url, form, 5000);
            JSONObject json = JSONUtil.parseObj(resp);
            int code = json.getInt("code", -1);
            if (code != 0) {
                return fail("Server酱返回: " + json.getStr("message", resp), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId("SC-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("Server酱发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
