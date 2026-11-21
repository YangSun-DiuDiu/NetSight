package com.netsight.modules.alert.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * PushPlus 模拟发送器（仅 dev / test profile 加载）。
 *
 * <p>与生产发送器互斥，避免 ChannelRegistry 中同一 channelType 出现两个实现。
 * 模拟器不解析租户 Token、不调用外网，仅打印日志并返回成功。
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class MockPushplusChannelSender implements NotificationChannelSender {

    public static final String CHANNEL_TYPE = "pushplus";

    /** 通知标题前缀（内容模板已带前缀时可为空） */
    @Value("${netsight.alert.channel.pushplus.title-prefix:【运维告警】}")
    private String titlePrefix = "【运维告警】";

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public String getChannelName() {
        return "PushPlus（模拟发送）";
    }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        log.info("【PushPlus MOCK】租户={} 接收人={} 标题={}",
                request.getTenantId(), request.getReceiverList(), buildTitle(request));
        return SendResult.builder()
                .success(true)
                .thirdPartyMsgId("MOCK-PP-" + UUID.randomUUID().toString().substring(0, 8))
                .costTime(System.currentTimeMillis() - start)
                .build();
    }

    @Override
    public boolean healthCheck(Map<String, Object> config) {
        return true;
    }

    private String buildTitle(SendRequest request) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(titlePrefix)) {
            sb.append(titlePrefix);
        }
        Object title = request.getExtra() == null ? null : request.getExtra().get("title");
        sb.append(title == null ? "NetSight 设备告警" : title);
        return sb.toString();
    }
}
