package com.netsight.modules.system.service;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.JwtUtils;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.service.NotifyChannelService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock private JwtUtils jwtUtils;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotifyChannelService notifyChannelService;
    @Mock private ChannelRegistry channelRegistry;
    @Mock private RepairerAccountResolver repairerAccountResolver;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(sysUserMapper, sysUserRoleMapper, sysRolePermissionMapper,
                jwtUtils, redisTemplate, notifyChannelService, channelRegistry,
                repairerAccountResolver);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("123456");
    }

    private SysUser user(Long id, Long tenantId, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("user" + id);
        user.setRealName("维修员" + id);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private Repairer repairer(Long id, Long tenantId, Long userId, String phone, Integer status) {
        Repairer repairer = new Repairer();
        repairer.setId(id);
        repairer.setTenantId(tenantId);
        repairer.setUserId(userId);
        repairer.setName("维修员");
        repairer.setPhone(phone);
        repairer.setStatus(status);
        return repairer;
    }

    @Test
    void h5LoginReturnsRepairerId() {
        SysUser user = user(22L, 14L, "19900000002", 1);
        Repairer repairer = repairer(101L, 14L, 22L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(repairer));
        when(sysUserRoleMapper.selectRoleKeysByUserId(22L)).thenReturn(List.of("repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(22L)).thenReturn(List.of(4L));
        when(sysRolePermissionMapper.selectPermKeysByRoleId(4L))
                .thenReturn(List.of("workorder:repairer:list"));
        when(jwtUtils.createToken(any())).thenReturn("token");

        Map<String, Object> result = service.loginByPhone("19900000002", "123456", "m");

        assertEquals("token", result.get("token"));
        assertEquals(101L, ((LoginUser) result.get("user")).getRepairerId());
    }

    @Test
    void h5LoginRejectsUserWithoutRepairerArchive() {
        SysUser user = user(30L, 14L, "19900000003", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 30L)).thenReturn(List.of());
        when(repairerAccountResolver.listLegacyByPhone(14L, "19900000003")).thenReturn(List.of());

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000003", "123456", "m"));

        assertEquals("当前账号未关联维修人员档案，请联系管理员", exception.getMessage());
    }

    @Test
    void h5LoginRejectsDisabledAccount() {
        SysUser user = user(22L, 14L, "19900000002", 0);
        Repairer repairer = repairer(101L, 14L, 22L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(repairer));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "m"));

        assertEquals("当前账号已被禁用，请联系管理员", exception.getMessage());
    }

    @Test
    void h5LoginRejectsMultipleRepairerArchives() {
        SysUser user = user(22L, 14L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        when(repairerAccountResolver.listBound(14L, 22L)).thenReturn(List.of(
                repairer(101L, 14L, 22L, "19900000002", 1),
                repairer(102L, 14L, 22L, "19900000002", 1)));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "m"));

        assertEquals("手机号关联多个维修人员账号，请联系管理员处理", exception.getMessage());
    }

    @Test
    void pcLoginStillRejectsMultipleEnabledAccounts() {
        SysUser first = user(22L, 14L, "19900000002", 1);
        SysUser second = user(23L, 14L, "19900000002", 1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(first, second));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.loginByPhone("19900000002", "123456", "pc"));

        assertEquals("手机号关联多个账号，请联系管理员", exception.getMessage());
    }
}
