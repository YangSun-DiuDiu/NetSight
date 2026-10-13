package com.netsight.modules.workflow.handler;

import com.netsight.framework.security.SecurityUtils;
import org.dromara.warm.flow.core.handler.PermissionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Warm-Flow 审批权限处理器
 *
 * 引擎判断"当前用户是否有权处理当前节点任务"：
 * - permissions()：当前用户的权限标识集合 = 用户ID + 角色标识。
 *   必须包含用户ID：审批认领会把 flow_user.processed_by 改写为具体用户ID，
 *   checkAuth 用 FlowParams.permissionFlag（懒加载自 permissions()）与
 *   task.userList.processedBy 做交集，含用户ID 才能命中认领后的任务。
 *   角色标识用于"按角色选审批人"（节点 permission_flag 配置 tenant_admin 等）。
 * - getHandler()：当前用户ID（引擎记录处理人）
 *
 * 流程设计器配置审批节点时，审批人权限选择"角色"，填写系统角色标识（tenant_admin 等）
 * 即与本实现匹配，实现"按角色选审批人"。
 *
 * 配置入口：application.yml → warm-flow.permission-handler-path
 */
public class NamsPermissionHandler implements PermissionHandler {

    @Override
    public List<String> permissions() {
        var loginUser = SecurityUtils.getLoginUser();
        List<String> perms = new ArrayList<>();
        // 用户ID 必含：与认领后的 processed_by（用户ID）交集，保证 checkAuth 放行
        if (loginUser != null && loginUser.getUserId() != null) {
            perms.add(String.valueOf(loginUser.getUserId()));
        }
        var roles = loginUser.getRoles();
        // 超级管理员拥有全部角色能力：放行所有流程节点审批（含租户管理员/仓库管理员等节点）
        if (roles != null && roles.contains("super_admin")) {
            perms.addAll(List.of("super_admin", "tenant_admin", "ops", "repairer", "device_admin", "warehouse_admin"));
        } else if (roles != null) {
            perms.addAll(roles);
        }
        return perms;
    }

    @Override
    public String getHandler() {
        return String.valueOf(SecurityUtils.getUserId());
    }
}
