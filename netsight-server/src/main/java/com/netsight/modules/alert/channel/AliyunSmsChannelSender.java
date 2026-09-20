package com.netsight.modules.alert.channel;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 阿里云短信通道（channelType = aliyun_sms）
 * config_json: {accessKeyId, accessKeySecret, signName, templateCode, regionId(默认cn-hangzhou)}
 * 接收人：request.receiverList（手机号，逗号分隔）
 */
@Slf4j
@Component
public class AliyunSmsChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "aliyun_sms"; }

    @Override
    public String getChannelName() { return "阿里云短信"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null) return fail("未配置阿里云短信参数", start);
            String ak = (String) cfg.get("accessKeyId");
            String sk = (String) cfg.get("accessKeySecret");
            String sign = (String) cfg.get("signName");
            String tpl = (String) cfg.get("templateCode");
            String region = (String) cfg.getOrDefault("regionId", "cn-hangzhou");
            if (!StringUtils.hasText(ak) || !StringUtils.hasText(sk)
                    || !StringUtils.hasText(sign) || !StringUtils.hasText(tpl)) {
                return fail("阿里云短信参数不完整", start);
            }
            if (request.getReceiverList() == null || request.getReceiverList().isEmpty()) {
                return fail("短信接收手机号为空", start);
            }

            Config config = new Config()
                    .setAccessKeyId(ak)
                    .setAccessKeySecret(sk)
                    .setEndpoint("dysmsapi." + region + ".aliyuncs.com")
                    .setRegionId(region);
            Client client = new Client(config);

            String phones = String.join(",", request.getReceiverList());
            SendSmsRequest req = new SendSmsRequest()
                    .setPhoneNumbers(phones)
                    .setSignName(sign)
                    .setTemplateCode(tpl)
                    .setTemplateParam(buildTemplateParam(request));

            SendSmsResponse resp = client.sendSms(req);
            String code = resp.getBody().getCode();
            if (!"OK".equals(code)) {
                return fail("阿里云短信返回: " + resp.getBody().getMessage(), start);
            }
            return SendResult.builder().success(true).thirdPartyMsgId(resp.getBody().getBizId())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("阿里云短信发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    /** 模板变量：把 contentVars 转 JSON（阿里云要求 JSON 字符串） */
    private String buildTemplateParam(SendRequest request) {
        if (request.getContentVars() == null || request.getContentVars().isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        request.getContentVars().forEach((k, v) ->
                sb.append("\"").append(k).append("\":\"").append(v).append("\",")
        );
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
