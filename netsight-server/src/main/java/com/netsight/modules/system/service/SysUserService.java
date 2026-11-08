package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final RepairerMapper repairerMapper;
    private final PasswordEncoder passwordEncoder;

    private static final String EXTERNAL_USERNAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

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
        boolean bound = isBoundToRepairer(exist.getTenantId(), user.getId());
        if (bound && user.getRealName() != null && !StringUtils.hasText(user.getRealName())) {
            throw new ServiceException(500, "已绑定维修人员的用户必须维护姓名");
        }
        if (bound && user.getPhone() != null && !StringUtils.hasText(user.getPhone())) {
            throw new ServiceException(500, "已绑定维修人员的用户必须维护手机号");
        }
        if (bound && user.getPhone() != null && !Objects.equals(exist.getPhone(), user.getPhone())) {
            if (countUsersByPhoneInTenant(exist.getTenantId(), user.getPhone(), user.getId()) > 0) {
                throw new ServiceException(500, "该手机号已被其他用户使用");
            }
            if (repairerMapper.selectCount(new LambdaQueryWrapper<Repairer>()
                    .eq(Repairer::getTenantId, exist.getTenantId())
                    .eq(Repairer::getPhone, user.getPhone())) > 0) {
                throw new ServiceException(500, "该手机号已存在其他维修人员档案");
            }
        }
        boolean profileChanged =
                (user.getRealName() != null && !Objects.equals(exist.getRealName(), user.getRealName()))
                        || (user.getPhone() != null && !Objects.equals(exist.getPhone(), user.getPhone()));
        if (profileChanged) {
            String realName = user.getRealName() != null ? user.getRealName() : exist.getRealName();
            String phone = user.getPhone() != null ? user.getPhone() : exist.getPhone();
            syncRepairerProfile(user.getId(), realName, phone);
        }
        sysUserMapper.updateById(update);
        List<Long> currentRoleIds = sysUserRoleMapper.selectRoleIdsByUserId(user.getId());
        List<Long> roleIds = user.getRoleIds() == null
                ? (currentRoleIds == null ? new ArrayList<>() : new ArrayList<>(currentRoleIds))
                : new ArrayList<>(user.getRoleIds());
        if (bound) {
            Long repairerRoleId = requireRoleId("repairer");
            if (!roleIds.contains(repairerRoleId)) {
                roleIds.add(repairerRoleId);
            }
        }
        bindRoles(user.getId(), roleIds);
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
        if (isBoundToRepairer(exist.getTenantId(), userId)) {
            throw new ServiceException(500, "该用户已绑定维修人员档案，请先删除维修人员档案");
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
     * 外聘维修人员建号：随机登录名 + 随机密码（BCrypt）+ repairer 角色。
     * 密码不返回、不落日志，H5 只走手机号验证码登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public SysUser createRepairerAccount(Long tenantId, String realName, String phone) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setStatus(1);
        user.setUsername(generateExternalUsername(tenantId));
        String rawPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        user.setPassword(passwordEncoder.encode(rawPassword));
        sysUserMapper.insert(user);
        ensureRole(user.getId(), "repairer");
        log.info("外聘维修人员账号已创建 tenantId={} userId={} username={}",
                tenantId, user.getId(), user.getUsername());
        return user;
    }

    /**
     * 追加单个角色，不清空用户原有角色。
     */
    public void ensureRole(Long userId, String roleKey) {
        Long roleId = requireRoleId(roleKey);
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds != null && roleIds.contains(roleId)) {
            return;
        }
        sysUserRoleMapper.insertUserRole(userId, roleId);
    }

    public boolean hasRole(Long userId, String roleKey) {
        List<String> roleKeys = sysUserRoleMapper.selectRoleKeysByUserId(userId);
        return roleKeys != null && roleKeys.contains(roleKey);
    }

    public boolean isBoundToRepairer(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return false;
        }
        return repairerMapper.selectCount(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getTenantId, tenantId)
                .eq(Repairer::getUserId, userId)) > 0;
    }

    public void syncRepairerProfile(Long userId, String realName, String phone) {
        repairerMapper.update(null, new LambdaUpdateWrapper<Repairer>()
                .eq(Repairer::getUserId, userId)
                .set(Repairer::getName, realName)
                .set(Repairer::getPhone, phone));
    }

    /**
     * 删除外聘档案后处理账号：账号角色只有 repairer 时禁用，有其他角色时保留并记日志。
     */
    public void disableRepairerAccountIfOnlyRole(Long userId) {
        List<String> roleKeys = getRoleKeys(userId);
        if (roleKeys != null && roleKeys.size() == 1 && roleKeys.contains("repairer")) {
            SysUser update = new SysUser();
            update.setId(userId);
            update.setStatus(0);
            sysUserMapper.updateById(update);
            log.info("外聘维修人员账号已禁用 userId={}", userId);
            return;
        }
        log.info("外聘维修人员账号保留，存在其他角色 userId={} roles={}", userId, roleKeys);
    }

    public long countUsersByPhoneInTenant(Long tenantId, String phone, Long excludeUserId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getPhone, phone);
        if (excludeUserId != null) {
            wrapper.ne(SysUser::getId, excludeUserId);
        }
        return sysUserMapper.selectCount(wrapper);
    }

    public List<SysUser> listUnboundUsersByPhone(Long tenantId, String phone) {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getPhone, phone)
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        Set<Long> boundUserIds = repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                        .eq(Repairer::getTenantId, tenantId)
                        .in(Repairer::getUserId, userIds))
                .stream()
                .map(Repairer::getUserId)
                .collect(Collectors.toSet());
        return users.stream()
                .filter(user -> !boundUserIds.contains(user.getId()))
                .toList();
    }

    private Long requireRoleId(String roleKey) {
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, roleKey)
                .eq(SysRole::getStatus, 1)
                .last("LIMIT 1"));
        if (role == null) {
            throw new ServiceException(500, "维修人员角色未配置，请联系管理员");
        }
        return role.getId();
    }

    private String generateExternalUsername(Long tenantId) {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder suffix = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                suffix.append(EXTERNAL_USERNAME_CHARS.charAt(
                        random.nextInt(EXTERNAL_USERNAME_CHARS.length())));
            }
            String username = "ext_" + tenantId + "_" + suffix;
            if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, username)) == 0) {
                return username;
            }
        }
        throw new ServiceException(500, "登录账号生成失败，请重试");
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
