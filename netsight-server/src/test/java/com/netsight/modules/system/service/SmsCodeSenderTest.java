package com.netsight.modules.system.service;

import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.channel.SendRequest;
import com.netsight.modules.alert.channel.SendResult;
import com.netsight.modules.alert.service.NotifyChannelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsCodeSenderTest {

    @Mock private NotifyChannelService notifyChannelService;
    @Mock private ChannelRegistry channelRegistry;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private NotificationChannelSender channelSender;

    @Test
    void mockSenderAlwaysReturnsConfiguredCodeAndRelaxedLimit() {
        MockSmsCodeSender sender = new MockSmsCodeSender();
        ReflectionTestUtils.setField(sender, "mockCode", "654321");

        assertEquals("654321", sender.generateCode());
        assertEquals(10, sender.rateLimitSeconds());
        sender.send("13800000000", "654321", "netsight:sms:code:13800000000");
    }

    @Test
    void realSenderGeneratesSixDigitCodeAndUsesProductionLimit() {
        RealSmsCodeSender sender = new RealSmsCodeSender(
                notifyChannelService, channelRegistry, redisTemplate);

        assertTrue(sender.generateCode().matches("\\d{6}"));
        assertEquals(60, sender.rateLimitSeconds());
    }

    @Test
    void realSenderDelegatesToConfiguredSmsChannel() {
        when(notifyChannelService.resolveSystemChannel("平台登录认证", 1L))
                .thenReturn(Map.of(
                        "channelType", "aliyun_sms",
                        "config", Map.of("signName", "NetSight")));
        when(channelRegistry.get("aliyun_sms")).thenReturn(channelSender);
        when(channelSender.send(any(SendRequest.class)))
                .thenReturn(SendResult.builder().success(true).thirdPartyMsgId("sms-1").build());

        RealSmsCodeSender sender = new RealSmsCodeSender(
                notifyChannelService, channelRegistry, redisTemplate);
        sender.send("13800000000", "123456", "netsight:sms:code:13800000000");

        ArgumentCaptor<SendRequest> captor = ArgumentCaptor.forClass(SendRequest.class);
        verify(channelSender).send(captor.capture());
        assertEquals(List.of("13800000000"), captor.getValue().getReceiverList());
        assertEquals("123456", captor.getValue().getContentVars().get("code"));
        verify(redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void realSenderClearsCodeWhenSmsChannelMissing() {
        when(notifyChannelService.resolveSystemChannel("平台登录认证", 1L)).thenReturn(null);

        RealSmsCodeSender sender = new RealSmsCodeSender(
                notifyChannelService, channelRegistry, redisTemplate);

        assertThrows(RuntimeException.class,
                () -> sender.send("13800000000", "123456", "netsight:sms:code:13800000000"));
        verify(redisTemplate).delete("netsight:sms:code:13800000000");
    }
}
