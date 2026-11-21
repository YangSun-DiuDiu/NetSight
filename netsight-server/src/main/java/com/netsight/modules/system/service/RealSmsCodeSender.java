package com.netsight.modules.system.service;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.channel.SendRequest;
import com.netsight.modules.alert.channel.SendResult;
import com.netsight.modules.alert.service.NotifyChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生产环境验证码策略：随机 6 位验证码，通过通知中心真实短信通道发送。
 *
 * <p>仅在非 dev/test/staging profile 加载；dev/test/staging 由 {@link MockSmsCodeSender} 替代。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!dev & !test & !staging")
public class RealSmsCodeSender implements SmsCodeSender {

    private final NotifyChannelService notifyChannelService;
    private final ChannelRegistry channelRegistry;
    private final StringRedisTemplate redisTemplate;

    /** 生产模式登录短信通道实例名（渠道管理里的实例名称） */
    @Value("${netsight.sms.login-channel-name:平台登录认证}")
    private String loginChannelName = "平台登录认证";

    /** 该通道实例所属租户（登录前无租户上下文，固定超管租户） */
    @Value("${netsight.sms.login-tenant-id:1}")
    private Long loginTenantId = 1L;

    @Override
    public String generateCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }

    @Override
    public void send(String phone, String code, String redisKey) {
        Map<String, Object> ch = notifyChannelService.resolveSystemChannel(loginChannelName, loginTenantId);
        if (ch == null) {
            redisTemplate.delete(redisKey);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(),
                    "登录短信通道未配置（实例：" + loginChannelName + "），请联系管理员");
        }
        NotificationChannelSender sender = channelRegistry.get((String) ch.get("channelType"));
        if (sender == null) {
            redisTemplate.delete(redisKey);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "短信通道实现未加载");
        }
        SendRequest request = SendRequest.builder()
                .receiverList(List.of(phone))
                .contentVars(Map.of("code", code))
                .channelConfig((Map<String, Object>) ch.get("config"))
                .build();
        SendResult result = sender.send(request);
        if (result == null || !result.isSuccess()) {
            redisTemplate.delete(redisKey);
            String msg = result == null ? "未知错误" : result.getErrorMsg();
            log.error("登录短信发送失败 phone={} err={}", phone, msg);
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "短信发送失败：" + msg);
        }
        log.info("登录短信已发送 phone={} bizId={}", phone, result.getThirdPartyMsgId());
    }

    @Override
    public int rateLimitSeconds() {
        return 60;
    }
}
