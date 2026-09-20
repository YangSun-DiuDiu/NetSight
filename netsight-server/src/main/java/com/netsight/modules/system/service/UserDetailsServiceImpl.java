package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户详情服务
 * 供 Spring Security 认证流程加载用户信息
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .last("LIMIT 1"));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new ServiceException(ResultCode.USER_DISABLED);
        }
        return buildLoginUser(user);
    }

    /**
     * 构造 LoginUser（角色/权限从数据库加载，超管标识以 super_admin 角色为准）
     */
    private LoginUser buildLoginUser(SysUser user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setRealName(user.getRealName());
        loginUser.setPhone(user.getPhone());
        loginUser.setTenantId(user.getTenantId());
        Set<String> roles = new java.util.HashSet<>(sysUserRoleMapper.selectRoleKeysByUserId(user.getId()));
        loginUser.setRoles(roles);
        loginUser.setPermissions(Set.of());
        loginUser.setIsSuperAdmin(roles.contains("super_admin"));
        return loginUser;
    }
}
