package com.netsight.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境模拟开关守卫（仅 prod profile 加载）。
 *
 * <p>生产路径上的 Mock 必须不可达：一旦检测到任一模拟开关为 true，直接抛异常阻止启动，
 * 避免“发送成功”的假象污染通知日志、掩盖真实告警失败。
 * dev/test profile 不加载本守卫，模拟发送由对应的 Mock Sender 接管。
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionMockGuard {

    @Value("${netsight.sms.mock-mode:${AUTH_SMS_MOCK:false}}")
    private boolean smsMockMode;

    @Value("${netsight.alert.channel.wechat.mock-mode:${WECHAT_MOCK:false}}")
    private boolean wechatMockMode;

    @Value("${netsight.alert.channel.pushplus.mock-mode:${PUSHPLUS_MOCK:false}}")
    private boolean pushplusMockMode;

    @PostConstruct
    public void verify() {
        if (smsMockMode) {
            throw fail("netsight.sms.mock-mode");
        }
        if (wechatMockMode) {
            throw fail("netsight.alert.channel.wechat.mock-mode");
        }
        if (pushplusMockMode) {
            throw fail("netsight.alert.channel.pushplus.mock-mode");
        }
        log.info("【生产守卫】模拟发送开关均为关闭状态，真实通道已启用");
    }

    private IllegalStateException fail(String key) {
        return new IllegalStateException("生产环境禁止开启模拟发送：" + key + "=true");
    }
}
