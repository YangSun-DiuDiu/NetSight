package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.SysRole;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysRoleMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户管理服务
 * 用户 CRUD、状态管理、重置密码、分配角色
 * 多租户数据隔离：新增/查询默认使用当前登录租户 ID（超管可管理全部）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SysUserService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 分页查询用户（租户隔离：普通管理员只能看本租户用户）
     */
    public PageResult<SysUser> pageUser(long pageNum, long pageSize, String username, String phone, Integer status) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(username), SysUser::getUsername, username)
                .like(StringUtils.hasText(phone), SysUser::getPhone, phone)
                .eq(status != null, SysUser::getStatus, status);
        // 数据隔离：非超管只能看本租户用户
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(SysUser::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> result = sysUserMapper.selectPage(page, wrapper);
        // 隐藏密码字段
        result.getRecords().forEach(u -> u.setPassword(null));
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 新增用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void addUser(SysUser user) {
        // 用户名唯一
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, user.getUsername()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "登录账号已存在");
        }
        // 租户归属：超管建号可指定租户；普通管理员建号归本租户
        if (SecurityUtils.isSuperAdmin() && user.getTenantId() != null) {
            // 使用指定租户
        } else {
            user.setTenantId(SecurityUtils.getTenantId());
        }
        // 默认密码 admin123（BCrypt），符合开发规范
        user.setPassword(passwordEncoder.encode("admin123"));
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        sysUserMapper.insert(user);
        // 分配角色
        bindRoles(user.getId(), user.getRoleIds());
    }

    /**
     * 修改用户（不含密码）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(SysUser user) {
        SysUser exist = sysUserMapper.selectById(user.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.USER_NOT_FOUND);
        }
        // 数据隔离校验：非超管不能操作其他租户用户
        checkTenantPermission(exist.getTenantId());
        // 用户名唯一（排除自己）
        Long count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, user.getUsername())
                .ne(SysUser::getId, user.getId()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "登录账号已存在");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setUsername(user.getUsername());
        update.setRealName(user.getRealName());
        update.setPhone(user.getPhone());
        update.setStatus(user.getStatus());
        sysUserMapper.updateById(update);
        // 重新绑定角色
        bindRoles(user.getId(), user.getRoleIds());
    }

    /**
     * 删除用户（逻辑删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        // 禁止删除自己
        if (SecurityUtils.getUserId().equals(userId)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "不能删除当前登录用户");
        }
        SysUser exist = sysUserMapper.selectById(userId);
        if (exist == null) {
            throw new ServiceException(ResultCode.USER_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        // 【加固】绑定 super_admin 角色的内置管理员不允许删除（防自毁/误删后无法管理）
        if (isSuperAdminUser(userId)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "内置管理员不允许删除");
        }
        sysUserMapper.deleteById(userId);
        sysUserRoleMapper.deleteByUserId(userId);
    }

    /**
     * 重置密码为默认密码 admin123
     */
    public void resetPassword(Long userId) {
        SysUser exist = sysUserMapper.selectById(userId);
        if (exist == null) {
            throw new ServiceException(ResultCode.USER_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode("admin123"));
        sysUserMapper.updateById(update);
    }

    /**
     * 切换用户启用/禁用状态
     */
    public void changeStatus(Long userId, Integer status) {
        SysUser exist = sysUserMapper.selectById(userId);
        if (exist == null) {
            throw new ServiceException(ResultCode.USER_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        // 内置管理员（绑定 super_admin 角色）不允许禁用
        if (isSuperAdminUser(exist.getId()) && status == 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "内置管理员不允许禁用");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setStatus(status);
        sysUserMapper.updateById(update);
    }

    /**
     * 查询用户已分配的角色ID列表
     */
    public List<Long> getRoleIds(Long userId) {
        return sysUserRoleMapper.selectRoleIdsByUserId(userId);
    }

    /**
     * 查询用户已分配的角色编码列表（登录用）
     */
    public List<String> getRoleKeys(Long userId) {
        return sysUserRoleMapper.selectRoleKeysByUserId(userId);
    }

    /**
     * 绑定用户角色（先清后插）
     */
    private void bindRoles(Long userId, List<Long> roleIds) {
        sysUserRoleMapper.deleteByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        // 安全加固：非超管不允许分配 super_admin 角色（防止提权越权）
        if (!SecurityUtils.isSuperAdmin()) {
            SysRole superRole = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleKey, "super_admin"));
            if (superRole != null && roleIds.contains(superRole.getId())) {
                throw new ServiceException(ResultCode.FORBIDDEN);
            }
        }
        for (Long roleId : roleIds) {
            sysUserRoleMapper.insertUserRole(userId, roleId);
        }
    }

    /**
     * 数据隔离校验：非超管不能操作其他租户数据
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 判断用户是否绑定 super_admin 角色（内置管理员保护，替代按用户名"admin"硬编码判定）
     */
    private boolean isSuperAdminUser(Long userId) {
        try {
            SysRole superRole = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleKey, "super_admin")
                    .last("LIMIT 1"));
            if (superRole == null) {
                return false;
            }
            List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
            return roleIds != null && roleIds.contains(superRole.getId());
        } catch (Exception e) {
            log.warn("判断 super_admin 角色失败 userId={}: {}", userId, e.getMessage());
            return false;
        }
    }
}
