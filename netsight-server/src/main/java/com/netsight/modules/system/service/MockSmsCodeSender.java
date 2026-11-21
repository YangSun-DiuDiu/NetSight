package com.netsight.modules.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 开发/测试环境验证码策略：固定验证码，仅打印日志，不真实发送。
 *
 * <p>仅在 dev/test profile 加载；staging/prod 由 {@link RealSmsCodeSender} 真实发送。
 */
@Slf4j
@Component
@Profile({"dev", "test"})
public class MockSmsCodeSender implements SmsCodeSender {

    @Value("${netsight.sms.mock-code:123456}")
    private String mockCode;

    @Override
    public String generateCode() {
        return mockCode;
    }

    @Override
    public void send(String phone, String code, String redisKey) {
        log.info("【模拟验证码】手机号: {}, 验证码: {}", phone, code);
    }

    @Override
    public int rateLimitSeconds() {
        return 10;
    }
}
