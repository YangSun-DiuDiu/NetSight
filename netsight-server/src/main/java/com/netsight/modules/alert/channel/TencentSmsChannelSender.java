package com.netsight.modules.alert.channel;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 腾讯云短信通道（channelType = tencent_sms）
 * config_json: {secretId, secretKey, sdkAppId, signName, templateId, region(默认ap-guangzhou)}
 * 接收人：request.receiverList（手机号，+86 前缀由模板侧处理）
 */
@Slf4j
@Component
public class TencentSmsChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() { return "tencent_sms"; }

    @Override
    public String getChannelName() { return "腾讯云短信"; }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null) return fail("未配置腾讯云短信参数", start);
            String sid = (String) cfg.get("secretId");
            String skey = (String) cfg.get("secretKey");
            String appId = (String) cfg.get("sdkAppId");
            String sign = (String) cfg.get("signName");
            String tplId = String.valueOf(cfg.getOrDefault("templateId", ""));
            String region = (String) cfg.getOrDefault("region", "ap-guangzhou");

            if (!StringUtils.hasText(sid) || !StringUtils.hasText(skey)
                    || !StringUtils.hasText(appId) || !StringUtils.hasText(sign)) {
                return fail("腾讯云短信参数不完整", start);
            }
            if (request.getReceiverList() == null || request.getReceiverList().isEmpty()) {
                return fail("短信接收手机号为空", start);
            }

            Credential cred = new Credential(sid, skey);
            ClientProfile cp = new ClientProfile();
            cp.setHttpProfile(new com.tencentcloudapi.common.profile.HttpProfile());
            SmsClient client = new SmsClient(cred, region, cp);

            SendSmsRequest req = new SendSmsRequest();
            req.setSmsSdkAppId(appId);
            req.setSignName(sign);
            req.setTemplateId(tplId);
            req.setPhoneNumberSet(request.getReceiverList().toArray(new String[0]));
            // 模板参数：把 contentVars 值按顺序传入（腾讯云要求数组）
            req.setTemplateParamSet(buildParamArray(request));

            SendSmsResponse resp = client.SendSms(req);
            if (resp.getSendStatusSet() != null && resp.getSendStatusSet().length > 0) {
                String code = resp.getSendStatusSet()[0].getCode();
                if (!"Ok".equals(code)) {
                    return fail("腾讯云短信返回: " + resp.getSendStatusSet()[0].getMessage(), start);
                }
            }
            return SendResult.builder().success(true).thirdPartyMsgId("TC-" + System.currentTimeMillis())
                    .costTime(System.currentTimeMillis() - start).build();
        } catch (Exception e) {
            log.error("腾讯云短信发送失败: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    private String[] buildParamArray(SendRequest request) {
        if (request.getContentVars() == null || request.getContentVars().isEmpty()) {
            return new String[0];
        }
        return request.getContentVars().values().stream().map(String::valueOf).toArray(String[]::new);
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) { return true; }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
