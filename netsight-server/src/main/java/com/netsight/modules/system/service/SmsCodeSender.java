package com.netsight.modules.system.service;

/**
 * 登录短信验证码生成与发送策略。
 *
 * <p>生产环境使用真实实现；开发/测试 profile 使用固定验证码实现。
 * 业务代码不再通过运行时开关决定是否 mock。
 */
public interface SmsCodeSender {

    /** 生成本次登录验证码 */
    String generateCode();

    /** 发送验证码；发送失败时应清理 Redis 中已写入的验证码 */
    void send(String phone, String code, String redisKey);

    /** 同一手机号发送间隔（秒） */
    int rateLimitSeconds();
}
