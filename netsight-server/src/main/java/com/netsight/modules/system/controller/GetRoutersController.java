package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.R;
import com.netsight.framework.security.LoginUser;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.SysPermission;
import com.netsight.modules.system.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 动态路由接口
 * 登录后前端调用，从 sys_permission 按角色动态生成菜单路由（与若依机制一致）
 * 顶级菜单（parent_id=0）→ component=Layout；子菜单 → 业务页面组件
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GetRoutersController {

    private final SysPermissionMapper sysPermissionMapper;

    /**
     * 获取路由
     * GET /getRouters
     */
    @GetMapping("/getRouters")
    public R<List<Map<String, Object>>> getRouters() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        // 超管：全部菜单；非超管：按角色关联查询
        List<SysPermission> menus;
        if (Boolean.TRUE.equals(loginUser.getIsSuperAdmin())) {
            menus = sysPermissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                    .eq(SysPermission::getPermType, "menu")
                    .orderByAsc(SysPermission::getSort));
        } else {
            menus = sysPermissionMapper.selectMenusByUserId(loginUser.getUserId());
        }
        return R.ok(buildRoutes(menus));
    }

    /**
     * 构建路由树：parent_id=0 为顶级（Layout），其余为子菜单
     */
    private List<Map<String, Object>> buildRoutes(List<SysPermission> menus) {
        List<Map<String, Object>> routes = new ArrayList<>();
        if (menus == null) {
            return routes;
        }
        Map<Long, Map<String, Object>> routeMap = new LinkedHashMap<>();
        // 第一遍：创建所有路由节点
        for (SysPermission perm : menus) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", toRouteName(perm.getPath()));
            node.put("path", perm.getPath());
            node.put("hidden", false);
            node.put("component", StringUtils.hasText(perm.getComponent()) ? perm.getComponent() : "Layout");
            node.put("meta", Map.of(
                    "title", perm.getPermName(),
                    "icon", StringUtils.hasText(perm.getIcon()) ? perm.getIcon() : "list",
                    "noCache", false
            ));
            node.put("children", new ArrayList<Map<String, Object>>());
            routeMap.put(perm.getId(), node);
        }
        // 第二遍：挂载父子关系
        for (SysPermission perm : menus) {
            Map<String, Object> node = routeMap.get(perm.getId());
            if (perm.getParentId() == null || perm.getParentId() == 0) {
                routes.add(node);
            } else {
                Map<String, Object> parent = routeMap.get(perm.getParentId());
                if (parent != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                    children.add(node);
                }
            }
        }
        // 顶级菜单处理：path 补全重定向；有子菜单时 children 挂到顶层
        for (Map<String, Object> route : routes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) route.get("children");
            route.put("redirect", "noRedirect");
            route.put("alwaysShow", true);
            if (!children.isEmpty()) {
                route.put("children", children);
            } else {
                route.remove("children");
            }
        }
        return routes;
    }

    /**
     * 路由 name：path 末段转首字母大写（如 /device → Device，list → List）
     */
    private String toRouteName(String path) {
        if (!StringUtils.hasText(path)) {
            return "Menu";
        }
        String segment = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            segment = path.substring(slash + 1);
        }
        if (segment.isBlank()) {
            return "Menu";
        }
        return Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
    }
}
