package com.netsight.modules.system.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysRole;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRoleMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysRoleMapper sysRoleMapper;
    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private RepairerMapper repairerMapper;
    @Mock private PasswordEncoder passwordEncoder;

    private SysUserService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 单测无 Spring 容器，需手动初始化 Repairer 的 MyBatis-Plus TableInfo，
        // 否则 LambdaUpdateWrapper.set(Repairer::getName, ...) 构造时急切解析列名会失败
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Repairer.class);
        service = new SysUserService(sysUserMapper, sysRoleMapper, sysUserRoleMapper,
                repairerMapper, passwordEncoder);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(21L);
        loginUser.setTenantId(14L);
        loginUser.setIsSuperAdmin(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SysUser user(Long id, Long tenantId, String realName, String phone, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setUsername("u" + id);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(status);
        return user;
    }

    private SysRole role(Long id, String roleKey) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleKey(roleKey);
        role.setStatus(1);
        return role;
    }

    @Test
    void createRepairerAccountCreatesUserWithRepairerRole() {
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("bcrypt-password");
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(99L)).thenReturn(List.of());
        when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        });

        SysUser created = service.createRepairerAccount(14L, "张三", "19900000009");

        assertEquals(99L, created.getId());
        assertTrue(created.getUsername().matches("ext_14_[a-z0-9]{8}"));
        verify(sysUserRoleMapper).insertUserRole(99L, 4L);
    }

    @Test
    void ensureRoleIsIdempotent() {
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));
        when(sysUserRoleMapper.selectRoleIdsByUserId(22L)).thenReturn(List.of(4L));

        service.ensureRole(22L, "repairer");

        verify(sysUserRoleMapper, org.mockito.Mockito.never()).insertUserRole(22L, 4L);
    }

    @Test
    void updateUserSyncsBoundRepairerProfile() {
        SysUser existing = user(22L, 14L, "维修员甲", "19900000002", 1);
        SysUser update = user(22L, 14L, "维修员甲改", "19900000008", 1);
        update.setRoleIds(List.of(3L));
        when(sysUserMapper.selectById(22L)).thenReturn(existing);
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(repairerMapper.selectCount(any())).thenReturn(1L, 0L);
        when(sysRoleMapper.selectOne(any())).thenReturn(role(4L, "repairer"));

        service.updateUser(update);

        verify(repairerMapper).update(isNull(), any());
        verify(sysUserRoleMapper).insertUserRole(22L, 3L);
        verify(sysUserRoleMapper).insertUserRole(22L, 4L);
    }

    @Test
    void deleteUserRejectsUserBoundToRepairer() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(22L, 14L, "维修员甲", "19900000002", 1));
        when(sysRoleMapper.selectOne(any())).thenReturn(null);
        when(repairerMapper.selectCount(any())).thenReturn(1L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.deleteUser(22L));

        assertEquals("该用户已绑定维修人员档案，请先删除维修人员档案", exception.getMessage());
    }
}
