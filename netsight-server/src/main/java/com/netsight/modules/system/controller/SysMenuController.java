package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.R;
import com.netsight.modules.system.entity.SysPermission;
import com.netsight.modules.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 菜单/权限管理接口
 * /system/menu/*
 */
@RestController
@RequestMapping("/system/menu")
@RequiredArgsConstructor
@Slf4j
public class SysMenuController {

    private final SysPermissionMapper sysPermissionMapper;

    /**
     * 权限树（角色分配权限时用）
     * 返回树形结构：menu -> 子menu -> button
     */
    @PreAuthorize("hasRole('super_admin')")
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        List<SysPermission> all = sysPermissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getSort));
        // 组装树（两层：菜单 -> 按钮）
        List<Map<String, Object>> tree = new ArrayList<>();
        for (SysPermission menu : all) {
            if (menu.getParentId() == 0) {
                tree.add(buildNode(menu, all));
            }
        }
        return R.ok(tree);
    }

    /**
     * 构建树节点
     */
    private Map<String, Object> buildNode(SysPermission node, List<SysPermission> all) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", node.getId());
        map.put("label", node.getPermName());
        map.put("permKey", node.getPermKey());
        map.put("permType", node.getPermType());
        // 子节点
        List<Map<String, Object>> children = new ArrayList<>();
        for (SysPermission child : all) {
            if (child.getParentId().equals(node.getId())) {
                children.add(buildNode(child, all));
            }
        }
        if (!children.isEmpty()) {
            map.put("children", children);
        }
        return map;
    }
}
