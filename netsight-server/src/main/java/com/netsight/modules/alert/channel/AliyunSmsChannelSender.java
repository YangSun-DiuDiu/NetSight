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
 * config_json: {accessKeyId, accessKeySecret, signName, templateCode,
 * regionId(默认cn-hangzhou)}
 * 接收人：request.receiverList（手机号，逗号分隔）
 */
@Slf4j
@Component
public class AliyunSmsChannelSender implements NotificationChannelSender {

    @Override
    public String getChannelType() {
        return "aliyun_sms";
    }

    @Override
    public String getChannelName() {
        return "阿里云短信";
    }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> cfg = request.getChannelConfig();
            if (cfg == null)
                return fail("未配置阿里云短信参数", start);
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

            // 优先使用实例配置的 endpoint（全国统一 dysmsapi.aliyuncs.com），未配置时兜底
            String endpoint = (String) cfg.getOrDefault("endpoint", "dysmsapi.aliyuncs.com");
            Config config = new Config()
                    .setAccessKeyId(ak)
                    .setAccessKeySecret(sk)
                    .setEndpoint(endpoint)
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

    /**
     * 
     * 构造阿里云短信模板参数（JSON）。
     * 规则：实例配置 config.templateVarNames 指定该阿里云模板需要哪些变量（逗号分隔，小驼峰，如
     * "deviceName,deviceIp,time"）；
     * 未配置时默认 "code"（兼容验证码模板）。变量名自动映射到系统变量（deviceName→device_name 等），从 contentVars
     * 取值。
     * 阿里云模板变量一般为小驼峰（${deviceName}），系统变量为下划线（device_name）。
     */
    private String buildTemplateParam(SendRequest request) {
        Map<String, Object> cfg = request.getChannelConfig();// 获取ChannelConfig的内容
        Map<String, Object> vars = request.getContentVars();// 获取ContentVars的内容，下划线变量名
        // 默认 code（验证码模板）；通知模板在实例配置里配 templateVarNames
        String varNames = (cfg != null && cfg.get("templateVarNames") != null)
                ? String.valueOf(cfg.get("templateVarNames"))
                : "code";
        String[] names = varNames.split(",");
        StringBuilder sb = new StringBuilder("{");
        for (String name : names) {
            String varName = name.trim();// 移除字符串首尾两端的 ASCII 空白字符，中间空白保留
            if (varName.isEmpty())
                continue;
            /* 阿里云小驼峰变量名 → 系统下划线变量名 */
            String sysKey = toSystemKey(varName);
            /**
             * 如果ContentVars的内容不为空，并且在map中存在通过模板变量转换过来的变量名为key的数据
             * 取不到/map为空时，默认返回空字符串，""
             */
            Object val = (vars != null && vars.get(sysKey) != null) ? vars.get(sysKey) : "";
            sb.append("\"").append(varName).append("\":\"").append(String.valueOf(val).replace("\"", "'"))
                    .append("\",");
        }
        if (sb.length() > 1)
            sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    /** 阿里云小驼峰变量名 → 系统下划线变量名 */
    private String toSystemKey(String varName) {
        switch (varName) {
            case "code":
                return "code";
            case "deviceName":
                return "device_name";
            case "deviceIp":
                return "device_ip";
            case "deviceType":
                return "device_type";
            case "time":
                return "time";
            case "location":
                return "location";
            case "tenantName":
                return "tenant_name";
            case "severity":
                return "severity";
            default:
                return varName;
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) {
        return true;
    }

    private SendResult fail(String msg, long start) {
        return SendResult.builder().success(false).errorMsg(msg).costTime(System.currentTimeMillis() - start).build();
    }
}
