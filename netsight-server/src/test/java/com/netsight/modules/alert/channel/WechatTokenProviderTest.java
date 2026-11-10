package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatTokenProviderTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private WechatHttpClient http;

    @Test
    void cachesTokenInRedis() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("netsight:wechat:token:app1")).thenReturn("cached-token");
        WechatTokenProvider p = new WechatTokenProvider(redis, http, JsonMapper.builder().build());
        assertEquals("cached-token", p.getToken("app1", "secret", false));
        verifyNoInteractions(http);
    }

    @Test
    void forceRefreshCallsStableToken() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(http.postJson(org.mockito.ArgumentMatchers.eq(WechatTokenProvider.STABLE_TOKEN_URL),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn("{\"access_token\":\"new-token\",\"expires_in\":7200}");
        WechatTokenProvider p = new WechatTokenProvider(redis, http, JsonMapper.builder().build());
        assertEquals("new-token", p.getToken("app2", "secret", true));
    }
}
