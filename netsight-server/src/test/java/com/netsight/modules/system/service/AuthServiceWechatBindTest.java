package com.netsight.modules.system.service;

import com.netsight.framework.security.JwtUtils;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.service.NotifyChannelService;
import com.netsight.modules.alert.wechat.WechatBindResult;
import com.netsight.modules.alert.wechat.WechatBindingService;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRolePermissionMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerAccountResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceWechatBindTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock private JwtUtils jwtUtils;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotifyChannelService notifyChannelService;
    @Mock private ChannelRegistry channelRegistry;
    @Mock private RepairerAccountResolver repairerAccountResolver;
    @Mock private WechatBindingService wechatBindingService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(sysUserMapper, sysUserRoleMapper, sysRolePermissionMapper,
                jwtUtils, redisTemplate, notifyChannelService, channelRegistry,
                repairerAccountResolver, wechatBindingService);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("123456");
    }

    @Test
    void loginSucceedsWhenBindingFails() {
        SysUser user = new SysUser();
        user.setId(22L);
        user.setTenantId(14L);
        user.setUsername("user22");
        user.setRealName("维修员");
        user.setPhone("19900000002");
        user.setStatus(1);
        Repairer repairer = new Repairer();
        repairer.setId(101L);
        repairer.setTenantId(14L);
        repairer.setUserId(22L);
        repairer.setStatus(1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(repairer));
        when(sysUserRoleMapper.selectRoleKeysByUserId(22L)).thenReturn(List.of());
        when(jwtUtils.createToken(any())).thenReturn("token");
        when(wechatBindingService.bindByTicket(anyLong(), anyString(), anyString()))
                .thenReturn(WechatBindResult.builder().bound(false)
                        .reason("CONTACT_NOT_REGISTERED").message("请联系管理员").build());

        Map<String, Object> result = service.loginByPhone(
                "19900000002", "123456", "m", "ticket-1");

        assertNotNull(result.get("token"));
        WechatBindResult bind = (WechatBindResult) result.get("wechatBind");
        assertEquals(false, bind.isBound());
    }

    @Test
    void loginWithoutTicketSkipsBinding() {
        SysUser user = new SysUser();
        user.setId(23L);
        user.setTenantId(14L);
        user.setUsername("user23");
        user.setPhone("19900000003");
        user.setStatus(1);
        Repairer repairer = new Repairer();
        repairer.setId(102L);
        repairer.setTenantId(14L);
        repairer.setUserId(23L);
        repairer.setStatus(1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 23L)).thenReturn(List.of(repairer));
        when(sysUserRoleMapper.selectRoleKeysByUserId(23L)).thenReturn(List.of());
        when(jwtUtils.createToken(any())).thenReturn("token");

        Map<String, Object> result = service.loginByPhone("19900000003", "123456", "m");

        WechatBindResult bind = (WechatBindResult) result.get("wechatBind");
        assertEquals("NO_TICKET", bind.getReason());
        assertFalse(bind.isBound());
    }
}
