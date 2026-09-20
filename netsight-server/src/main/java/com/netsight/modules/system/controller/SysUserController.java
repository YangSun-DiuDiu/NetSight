package com.netsight.modules.system.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.service.SysUserService;
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
 * 用户管理接口
 * /system/user/*
 */
@RestController
@RequestMapping("/system/user")
@RequiredArgsConstructor
@Slf4j
public class SysUserController {

    private final SysUserService sysUserService;

    /**
     * 分页查询用户
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @GetMapping("/list")
    public R<PageResult<SysUser>> list(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "10") long pageSize,
                                       @RequestParam(required = false) String username,
                                       @RequestParam(required = false) String phone,
                                       @RequestParam(required = false) Integer status) {
        return R.ok(sysUserService.pageUser(pageNum, pageSize, username, phone, status));
    }

    /**
     * 新增用户
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @PostMapping
    @Log(module="用户管理", action="新增用户")
    public R<Void> add(@RequestBody SysUser user) {
        sysUserService.addUser(user);
        return R.ok();
    }

    /**
     * 修改用户
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @PutMapping
    @Log(module="用户管理", action="修改用户")
    public R<Void> edit(@RequestBody SysUser user) {
        sysUserService.updateUser(user);
        return R.ok();
    }

    /**
     * 删除用户
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @DeleteMapping("/{userId}")
    @Log(module="用户管理", action="删除用户")
    public R<Void> remove(@PathVariable Long userId) {
        sysUserService.deleteUser(userId);
        return R.ok();
    }

    /**
     * 重置密码（恢复默认密码 admin123）
     */
    @PreAuthorize("hasRole('super_admin')")
    @PutMapping("/resetPwd/{userId}")
    @Log(module="用户管理", action="重置密码")
    public R<Void> resetPwd(@PathVariable Long userId) {
        sysUserService.resetPassword(userId);
        return R.ok();
    }

    /**
     * 切换启用/禁用状态
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @PutMapping("/status/{userId}/{status}")
    @Log(module="用户管理", action="修改用户状态")
    public R<Void> changeStatus(@PathVariable Long userId, @PathVariable Integer status) {
        sysUserService.changeStatus(userId, status);
        return R.ok();
    }

    /**
     * 查询用户已分配的角色ID列表
     */
    @PreAuthorize("hasRole('super_admin') or hasRole('tenant_admin')")
    @GetMapping("/roleIds/{userId}")
    public R<List<Long>> roleIds(@PathVariable Long userId) {
        return R.ok(sysUserService.getRoleIds(userId));
    }
}
