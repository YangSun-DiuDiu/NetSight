package com.netsight.modules.system.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.system.entity.SysRole;
import com.netsight.modules.system.service.SysRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import com.netsight.framework.aspectj.Log;

/**
 * 角色管理接口
 * /system/role/*
 */
@RestController
@RequestMapping("/system/role")
@RequiredArgsConstructor
@Slf4j
public class SysRoleController {

    private final SysRoleService sysRoleService;

    /**
     * 分页查询角色
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @GetMapping("/list")
    public R<PageResult<SysRole>> list(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "10") long pageSize,
                                       @RequestParam(required = false) String roleName,
                                       @RequestParam(required = false) String roleKey) {
        return R.ok(sysRoleService.pageRole(pageNum, pageSize, roleName, roleKey));
    }

    /**
     * 全部角色列表（分配用户角色时下拉用）
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @GetMapping("/all")
    public R<List<SysRole>> all() {
        return R.ok(sysRoleService.listAll());
    }

    /**
     * 新增角色
     */
    @PreAuthorize("hasRole('super_admin')")
    @PostMapping
    @Log(module="角色管理", action="新增角色")
    public R<Void> add(@RequestBody SysRole role) {
        sysRoleService.addRole(role);
        return R.ok();
    }

    /**
     * 修改角色
     */
    @PreAuthorize("hasRole('super_admin')")
    @PutMapping
    @Log(module="角色管理", action="修改角色")
    public R<Void> edit(@RequestBody SysRole role) {
        sysRoleService.updateRole(role);
        return R.ok();
    }

    /**
     * 删除角色
     */
    @PreAuthorize("hasRole('super_admin')")
    @DeleteMapping("/{roleId}")
    @Log(module="角色管理", action="删除角色")
    public R<Void> remove(@PathVariable Long roleId) {
        sysRoleService.deleteRole(roleId);
        return R.ok();
    }

    /**
     * 查询角色已分配的权限ID列表
     */
    @PreAuthorize("hasRole('super_admin')")
    @GetMapping("/permIds/{roleId}")
    public R<List<Long>> permIds(@PathVariable Long roleId) {
        return R.ok(sysRoleService.getPermIds(roleId));
    }
}
