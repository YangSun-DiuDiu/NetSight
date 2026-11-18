package com.netsight.modules.alert.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.entity.NotifyChannel;
import com.netsight.modules.alert.mapper.NotifyChannelMapper;
import com.netsight.common.util.TokenCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyChannelServiceTest {

    @Mock
    private NotifyChannelMapper channelMapper;

    @Mock
    private ChannelRegistry channelRegistry;

    @Mock
    private TokenCrypto tokenCrypto;

    private NotifyChannelService service;

    @BeforeEach
    void setUp() {
        service = new NotifyChannelService(
                channelMapper, JsonMapper.builder().build(), tokenCrypto, channelRegistry);
    }

    private NotifyChannel channel(Long id, Long tenantId, String appId, int enabled) {
        NotifyChannel c = new NotifyChannel();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setChannelType("wechat");
        c.setChannelName("服务号-" + id);
        c.setEnabled(enabled);
        c.setConfigJson("{\"appId\":\"" + appId + "\",\"appSecret\":\"secret\"}");
        return c;
    }

    @Test
    void resolveChannelsRejectsChannelOfAnotherTenant() {
        when(channelMapper.selectById(7L)).thenReturn(channel(7L, 7L, "wx-shared", 1));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.resolveChannels(List.of(7L), 1L));

        assertEquals("通道不属于当前租户，拒绝使用", exception.getMessage());
    }

    @Test
    void resolveChannelsRejectsMissingChannel() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.resolveChannels(List.of(7L), 1L));

        assertEquals("通道不存在或不属于当前租户，拒绝使用", exception.getMessage());
    }

    @Test
    void resolveChannelsSkipsDisabledOwnChannel() {
        when(channelMapper.selectById(20L)).thenReturn(channel(20L, 1L, "wx-shared", 0));

        List<Map<String, Object>> result = service.resolveChannels(List.of(20L), 1L);

        assertEquals(0, result.size());
    }

    @Test
    void resolveChannelsReturnsOwnChannel() {
        when(channelMapper.selectById(20L)).thenReturn(channel(20L, 1L, "wx-shared", 1));

        List<Map<String, Object>> result = service.resolveChannels(List.of(20L), 1L);

        assertEquals(1, result.size());
        assertEquals(20L, result.get(0).get("channelId"));
        assertEquals(1L, result.get(0).get("tenantId"));
        assertEquals("wx-shared", ((Map<?, ?>) result.get(0).get("config")).get("appId"));
    }

    @Test
    void assertChannelUsableRejectsChannelOfAnotherTenant() {
        when(channelMapper.selectById(7L)).thenReturn(channel(7L, 7L, "wx-shared", 1));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.assertChannelUsable(7L, 1L));

        assertEquals("绑定的通道不存在、未启用或不属于当前租户", exception.getMessage());
    }

    @Test
    void requireOAuthChannelRejectsExplicitChannelOfAnotherTenant() {
        when(channelMapper.selectById(7L)).thenReturn(channel(7L, 7L, "wx-shared", 1));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.requireOAuthChannel(7L, 1L));

        assertEquals("微信公众号通道不属于当前租户", exception.getMessage());
    }

    @Test
    void requireOAuthChannelDefaultSameAppIdPicksSmallestId() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(20L, 1L, "wx-shared", 1),
                channel(21L, 1L, "wx-shared", 1)));

        Map<String, Object> result = service.requireOAuthChannel(null, 1L);

        assertEquals(20L, result.get("channelId"));
        assertEquals("wx-shared", ((Map<?, ?>) result.get("config")).get("appId"));
    }

    @Test
    void requireOAuthChannelDefaultDifferentAppIdsAreRejected() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(20L, 1L, "wx-a", 1),
                channel(21L, 1L, "wx-b", 1)));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.requireOAuthChannel(null, 1L));

        assertEquals("存在多个微信服务号，请显式指定 OAuth 通道 ID", exception.getMessage());
    }

    @Test
    void requireTenantWechatChannelIdSameAppIdPicksSmallestId() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(20L, 1L, "wx-shared", 1),
                channel(21L, 1L, "wx-shared", 1)));

        assertEquals(20L, service.requireTenantWechatChannelId(1L));
    }

    @Test
    void requireTenantWechatChannelIdDifferentAppIdsAreRejected() {
        when(channelMapper.selectList(any())).thenReturn(List.of(
                channel(20L, 1L, "wx-a", 1),
                channel(21L, 1L, "wx-b", 1)));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.requireTenantWechatChannelId(1L));

        assertEquals("该租户存在多个微信服务号，请指定 channelId", exception.getMessage());
    }
}
