package com.netsight.modules.alert.wechat;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.channel.WechatHttpClient;
import com.netsight.modules.alert.service.NotifyChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatOAuthServiceTest {

    @Mock
    private NotifyChannelService notifyChannelService;

    @Mock
    private WechatHttpClient httpClient;

    @Mock
    private WechatBindingService bindingService;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WechatOAuthService service;

    @BeforeEach
    void setUp() {
        service = new WechatOAuthService(
                notifyChannelService, httpClient, bindingService, redis, JsonMapper.builder().build());
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void startUsesResolvedChannelInsteadOfFixedSeven() {
        when(notifyChannelService.requireOAuthChannel(null, 1L)).thenReturn(Map.of(
                "channelId", 20L,
                "tenantId", 1L,
                "channelType", "wechat",
                "channelName", "租户1服务号",
                "config", Map.of(
                        "appId", "wx-tenant-1",
                        "appSecret", "secret",
                        "oauthPublicBaseUrl", "https://api.example.com/prod-api")));

        WechatOAuthService.StartResult result = service.start(
                "/m/login", "login", null, null, 1L);

        assertTrue(result.getUrl().contains("appid=wx-tenant-1"));
        assertTrue(result.getUrl().contains("redirect_uri=https%3A%2F%2Fapi.example.com%2Fprod-api%2Fwechat%2Foauth%2Fcallback"));
        assertEquals("", result.getTicket());
    }

    @Test
    void callbackMapsChannelConfigErrorToChannelConfig() {
        when(valueOps.get(anyString())).thenReturn(
                "{\"mode\":\"bind\",\"redirect\":\"/alert/contact\",\"channelId\":7,"
                        + "\"tenantId\":1,\"contactId\":3,\"pollTicket\":\"poll\"}");
        when(redis.delete(anyString())).thenReturn(true);
        when(notifyChannelService.requireOAuthChannel(7L, 1L))
                .thenThrow(new ServiceException("通道不属于当前租户"));

        String redirect = service.callback("code", "state");

        assertEquals("/alert/contact?wechatBindError=CHANNEL_CONFIG", redirect);
    }
}
