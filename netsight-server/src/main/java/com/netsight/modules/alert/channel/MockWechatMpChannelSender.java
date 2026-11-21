package com.netsight.modules.alert.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信公众号模拟发送器（仅 dev / test profile 加载）。
 *
 * <p>与生产发送器互斥：同一 channelType 只允许一个实现注册到 ChannelRegistry。
 * 模拟器不调用微信接口、不读取 Token，逐人返回成功，便于本地与测试环境联调。
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class MockWechatMpChannelSender implements NotificationChannelSender {

    public static final String CHANNEL_TYPE = WechatMpChannelSender.CHANNEL_TYPE;

    private final WechatTemplatePayload payload;

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public String getChannelName() {
        return "微信公众号（模拟发送）";
    }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        Map<String, Object> cfg = request.getChannelConfig() == null
                ? Map.of() : request.getChannelConfig();
        String appId = text(cfg, "appId");
        String appSecret = text(cfg, "appSecret");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return fail("微信公众号通道实例缺少 appId / appSecret 配置", start);
        }

        Set<String> receivers = new LinkedHashSet<>();
        if (request.getReceiverList() != null) {
            for (String r : request.getReceiverList()) {
                if (StringUtils.hasText(r)) {
                    receivers.add(r.trim());
                }
            }
        }
        if (receivers.isEmpty()) {
            return fail("无有效微信接收人（联系人的 wechat_openid 为空）", start);
        }

        WechatTemplatePayload.Rendered rendered =
                payload.render(request, text(cfg, "templateId"));
        if (!StringUtils.hasText(rendered.templateId())) {
            return fail("微信公众号模板 ID 为空", start);
        }

        List<RecipientResult> results = new ArrayList<>();
        for (String openid : receivers) {
            results.add(RecipientResult.builder()
                    .receiver(mask(openid)).success(true).errcode(0).build());
        }
        log.info("【微信公众号 MOCK】appId={} 接收人数={} 模板={}",
                appId, receivers.size(), rendered.templateId());
        return SendResult.builder()
                .success(true)
                .retryable(false)
                .templateId(rendered.templateId())
                .renderedContent(rendered.summary())
                .recipientResults(results)
                .thirdPartyMsgId("MOCK")
                .costTime(System.currentTimeMillis() - start)
                .build();
    }

    @Override
    public boolean healthCheck(Map<String, Object> config) {
        if (config == null) {
            return false;
        }
        return StringUtils.hasText(text(config, "appId"))
                && StringUtils.hasText(text(config, "appSecret"))
                && (StringUtils.hasText(text(config, "templateId"))
                    || StringUtils.hasText(text(config, "content")));
    }

    private static SendResult fail(String msg, long start) {
        return SendResult.builder()
                .success(false)
                .retryable(false)
                .errorMsg(msg)
                .costTime(System.currentTimeMillis() - start)
                .build();
    }

    private static String text(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid) || openid.length() <= 10) {
            return "***";
        }
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }
}
