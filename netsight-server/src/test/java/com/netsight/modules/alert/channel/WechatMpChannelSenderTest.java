package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatMpChannelSenderTest {

    @Mock
    private WechatTokenProvider tokenProvider;
    @Mock
    private WechatHttpClient httpClient;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private WechatMpChannelSender sender() {
        WechatTemplatePayload payload =
                new WechatTemplatePayload(JsonMapper.builder().build());
        return new WechatMpChannelSender(tokenProvider, payload, httpClient, redis);
    }

    @Test
    void mockModeReturnsOneResultPerReceiver() {
        SendRequest req = SendRequest.builder()
                .receiverList(List.of("openid-1", "openid-2"))
                .channelConfig(Map.of("appId", "app", "appSecret", "sec",
                        "templateId", "tpl", "mockMode", true))
                .contentVars(Map.of("device_name", "dev"))
                .build();
        SendResult result = sender().send(req);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getRecipientResults().size());
        assertEquals("MOCK", result.getThirdPartyMsgId());
        verify(httpClient, never()).postJson(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void duplicateRecipientIsSkipped() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("LOCK"), any(Duration.class))).thenReturn(false);
        SendRequest req = SendRequest.builder()
                .receiverList(List.of("openid-1"))
                .channelConfig(Map.of("appId", "app", "appSecret", "sec", "templateId", "tpl"))
                .bizId("biz-1").tenantId(7L).contentVars(Map.of())
                .build();
        SendResult result = sender().send(req);
        assertTrue(result.isSuccess());
        assertTrue(result.getRecipientResults().get(0).isSkipped());
        assertEquals("duplicate", result.getRecipientResults().get(0).getSkipReason());
        verify(httpClient, never()).postJson(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void retriesTransientErrorThenSucceeds() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("LOCK"), any(Duration.class))).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(tokenProvider.getToken("app", "sec", false)).thenReturn("token");
        when(httpClient.postJson(anyString(), anyString(), any(Integer.class)))
                .thenReturn("{\"errcode\":-1,\"errmsg\":\"busy\"}")
                .thenReturn("{\"errcode\":0,\"msgid\":\"m1\"}");
        SendRequest req = SendRequest.builder()
                .receiverList(List.of("openid-1"))
                .channelConfig(Map.of("appId", "app", "appSecret", "sec", "templateId", "tpl",
                        "retryIntervals", "0,0"))
                .bizId("biz-1").tenantId(7L).contentVars(Map.of())
                .build();
        SendResult result = sender().send(req);
        assertTrue(result.isSuccess());
        assertEquals(1, result.getRecipientResults().get(0).getRetryCount());
        assertEquals("m1", result.getThirdPartyMsgId());
    }
}
