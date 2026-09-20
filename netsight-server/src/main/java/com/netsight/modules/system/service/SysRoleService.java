package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.SysPermission;
import com.netsight.modules.system.entity.SysRole;
import com.netsight.modules.system.mapper.SysPermissionMapper;
import com.netsight.modules.system.mapper.SysRoleMapper;
import com.netsight.modules.system.mapper.SysRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 角色管理服务
 * 角色 CRUD、权限分配
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SysRoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    /**
     * 分页查询角色
     */
    public PageResult<SysRole> pageRole(long pageNum, long pageSize, String roleName, String roleKey) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .like(StringUtils.hasText(roleName), SysRole::getRoleName, roleName)
                .like(StringUtils.hasText(roleKey), SysRole::getRoleKey, roleKey)
                .orderByAsc(SysRole::getRoleSort);
        Page<SysRole> result = sysRoleMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 全部角色列表（分配角色时用）
     * 安全加固：非超管不暴露 super_admin 角色，防止越权分配提权
     */
    public List<SysRole> listAll() {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getRoleSort);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.ne(SysRole::getRoleKey, "super_admin");
        }
        return sysRoleMapper.selectList(wrapper);
    }

    /**
     * 新增角色
     */
    public void addRole(SysRole role) {
        Long count = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, role.getRoleKey()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "角色编码已存在");
        }
        if (role.getStatus() == null) {
            role.setStatus(1);
        }
        sysRoleMapper.insert(role);
        // 分配权限
        bindPerms(role.getId(), role.getPermIds());
    }

    /**
     * 修改角色（支持两种场景：完整修改 / 仅分配权限）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(SysRole role) {
        SysRole exist = sysRoleMapper.selectById(role.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "角色不存在");
        }
        // 仅分配权限场景（前端只传 id + permIds）
        if (role.getRoleName() == null && role.getRoleKey() == null && role.getStatus() == null) {
            bindPerms(role.getId(), role.getPermIds());
            return;
        }
        // 内置角色不允许改编码
        if (role.getRoleKey() != null && isBuiltin(exist.getRoleKey()) && !exist.getRoleKey().equals(role.getRoleKey())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "内置角色编码不允许修改");
        }
        Long count = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, role.getRoleKey())
                .ne(SysRole::getId, role.getId()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "角色编码已存在");
        }
        SysRole update = new SysRole();
        update.setId(role.getId());
        update.setRoleName(role.getRoleName());
        update.setRoleKey(role.getRoleKey());
        update.setRoleSort(role.getRoleSort());
        update.setStatus(role.getStatus());
        update.setRemark(role.getRemark());
        sysRoleMapper.updateById(update);
        // 重新分配权限
        bindPerms(role.getId(), role.getPermIds());
    }

    /**
     * 删除角色（内置角色不允许删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long roleId) {
        SysRole exist = sysRoleMapper.selectById(roleId);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "角色不存在");
        }
        if (isBuiltin(exist.getRoleKey())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "内置角色不允许删除");
        }
        sysRoleMapper.deleteById(roleId);
        sysRolePermissionMapper.deleteByRoleId(roleId);
    }

    /**
     * 查询角色已分配的权限ID列表
     */
    public List<Long> getPermIds(Long roleId) {
        return sysRolePermissionMapper.selectPermIdsByRoleId(roleId);
    }

    /**
     * 查询角色拥有的权限标识列表（登录用）
     */
    public List<String> getPermKeys(Long roleId) {
        return sysRolePermissionMapper.selectPermKeysByRoleId(roleId);
    }

    /**
     * 绑定角色权限（先清后插）
     */
    private void bindPerms(Long roleId, List<Long> permIds) {
        sysRolePermissionMapper.deleteByRoleId(roleId);
        if (permIds == null || permIds.isEmpty()) {
            return;
        }
        for (Long permId : permIds) {
            sysRolePermissionMapper.insertRolePerm(roleId, permId);
        }
    }

    /**
     * 内置角色判断（不允许删除/改编码）
     */
    private boolean isBuiltin(String roleKey) {
        return List.of("super_admin", "tenant_admin", "ops", "repairer").contains(roleKey);
    }
}
