package com.netsight.modules.workorder.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.service.SysUserService;
import com.netsight.modules.workorder.dto.RepairerSaveResult;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepairerServiceTest {

    @Mock private RepairerMapper repairerMapper;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysUserService sysUserService;

    private RepairerService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测无 Spring 容器，需手动初始化 Repairer 的 MyBatis-Plus TableInfo，
        // 否则 LambdaQueryWrapper.eq(Repairer::getXxx, ...) 构造时急切解析列名会抛
        // "can not find lambda cache for this entity"
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Repairer.class);
        service = new RepairerService(repairerMapper, workOrderMapper, sysUserMapper, sysUserService);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(21L);
        loginUser.setTenantId(14L);
        loginUser.setIsSuperAdmin(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysUser sysUser(Long id, String realName, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(14L);
        user.setUsername("user" + id);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private Repairer repairerRequest(int type, Long userId, String name, String phone) {
        Repairer repairer = new Repairer();
        repairer.setRepairerType(type);
        repairer.setUserId(userId);
        repairer.setName(name);
        repairer.setPhone(phone);
        repairer.setStatus(1);
        repairer.setDeviceTypes("[\"network\"]");
        return repairer;
    }

    @Test
    void addInternalRepairerBindsExistingUserAndAppendsRole() {
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(sysUserMapper.selectByIdIgnoreTenant(22L)).thenReturn(user);
        when(repairerMapper.selectCount(any())).thenReturn(0L);
        when(repairerMapper.insert(any(Repairer.class))).thenAnswer(invocation -> {
            Repairer inserted = invocation.getArgument(0);
            inserted.setId(101L);
            return 1;
        });

        RepairerSaveResult result = service.addRepairer(repairerRequest(1, 22L, null, null));

        ArgumentCaptor<Repairer> captor = ArgumentCaptor.forClass(Repairer.class);
        verify(repairerMapper).insert(captor.capture());
        Repairer inserted = captor.getValue();
        assertEquals(14L, inserted.getTenantId());
        assertEquals(22L, inserted.getUserId());
        assertEquals(1, inserted.getRepairerType());
        assertEquals("维修员甲", inserted.getName());
        assertEquals("19900000002", inserted.getPhone());
        assertEquals(101L, result.getId());
        assertEquals("user22", result.getLoginUsername());
        assertFalse(result.isAccountCreated());
        verify(sysUserService).ensureRole(22L, "repairer");
    }

    @Test
    void addInternalRepairerRejectsDuplicateBinding() {
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(sysUserMapper.selectByIdIgnoreTenant(22L)).thenReturn(user);
        when(repairerMapper.selectCount(any())).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addRepairer(repairerRequest(1, 22L, null, null)));

        assertEquals("该用户已绑定其他维修人员档案", exception.getMessage());
    }

    @Test
    void addExternalRepairerCreatesAccountAndRepairer() {
        SysUser created = sysUser(99L, "张三", "19900000009", 1);
        created.setUsername("ext_14_ab12cd34");
        when(sysUserService.countUsersByPhoneInTenant(14L, "19900000009", null)).thenReturn(0L);
        when(repairerMapper.selectCount(any())).thenReturn(0L);
        when(sysUserService.createRepairerAccount(14L, "张三", "19900000009")).thenReturn(created);
        when(repairerMapper.insert(any(Repairer.class))).thenAnswer(invocation -> {
            Repairer inserted = invocation.getArgument(0);
            inserted.setId(102L);
            return 1;
        });

        RepairerSaveResult result = service.addRepairer(
                repairerRequest(2, null, "张三", "19900000009"));

        assertEquals(102L, result.getId());
        assertEquals("ext_14_ab12cd34", result.getLoginUsername());
        assertTrue(result.isAccountCreated());
    }

    @Test
    void addExternalRepairerRejectsOccupiedPhone() {
        when(sysUserService.countUsersByPhoneInTenant(14L, "19900000009", null)).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addRepairer(repairerRequest(2, null, "张三", "19900000009")));

        assertEquals("该手机号已被本租户用户占用，请改用内聘选择", exception.getMessage());
    }

    @Test
    void bindAccountForInternalRepairerUsesUnboundSamePhoneUser() {
        Repairer existing = new Repairer();
        existing.setId(101L);
        existing.setTenantId(14L);
        existing.setRepairerType(0);
        existing.setName("维修员甲");
        existing.setPhone("19900000002");
        existing.setStatus(1);
        SysUser user = sysUser(22L, "维修员甲", "19900000002", 1);
        when(repairerMapper.selectOne(any())).thenReturn(existing);
        when(sysUserService.listUnboundUsersByPhone(14L, "19900000002")).thenReturn(List.of(user));

        RepairerSaveResult result = service.bindAccount(101L, 1);

        assertEquals(101L, result.getId());
        assertEquals("user22", result.getLoginUsername());
        assertFalse(result.isAccountCreated());
        verify(repairerMapper).updateById(existing);
        verify(sysUserService).ensureRole(22L, "repairer");
    }

    @Test
    void removeExternalRepairerDisablesAccountWhenOnlyRepairerRole() {
        Repairer existing = new Repairer();
        existing.setId(102L);
        existing.setTenantId(14L);
        existing.setRepairerType(2);
        existing.setUserId(99L);
        existing.setStatus(1);
        when(repairerMapper.selectOne(any())).thenReturn(existing);

        service.removeRepairer(102L);

        verify(sysUserService).disableRepairerAccountIfOnlyRole(99L);
        verify(repairerMapper).deleteById(102L);
    }

    @Test
    void updateExternalRepairerPhoneSyncsSysUser() {
        Repairer existing = new Repairer();
        existing.setId(102L);
        existing.setTenantId(14L);
        existing.setRepairerType(2);
        existing.setUserId(99L);
        existing.setName("张三");
        existing.setPhone("19900000009");
        existing.setStatus(1);
        when(repairerMapper.selectOne(any())).thenReturn(existing);
        when(sysUserService.countUsersByPhoneInTenant(14L, "19900000099", 99L)).thenReturn(0L);
        when(repairerMapper.selectCount(any())).thenReturn(0L);

        Repairer request = new Repairer();
        request.setId(102L);
        request.setPhone("19900000099");
        request.setStatus(1);
        service.updateRepairer(request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        SysUser synced = captor.getValue();
        assertEquals(99L, synced.getId());
        assertEquals("19900000099", synced.getPhone());
        assertEquals("张三", synced.getRealName());
    }
}
