-- 流程定义/设计器收敛为超级管理员专属
-- 日期：2026-11-20
-- 说明：
--   1. 流程定义、流程设计器菜单仅保留给 super_admin（超管按角色通配拥有全部权限，无需显式授权）。
--   2. 租户管理员（tenant_admin）、运维人员（ops）不再显示流程定义/设计器入口，仅保留
--      “流程审批”父菜单与“我的发起”，审批待办仍可正常使用。
--   3. 后端 /warm-flow/** 已增加 super_admin 访问控制，菜单调整与后端校验共同生效。

DELETE rp
FROM sys_role_permission rp
JOIN sys_role r ON r.id = rp.role_id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE r.role_key IN ('tenant_admin', 'ops')
  AND p.perm_key IN ('workflow:definition:menu', 'workflow:designer:menu');

-- 验证：以下查询应只返回 super_admin 相关记录（超管通常无显式行，返回空即正常）
SELECT r.role_key, p.perm_key
FROM sys_role r
JOIN sys_role_permission rp ON rp.role_id = r.id
JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.perm_key IN ('workflow:definition:menu', 'workflow:designer:menu')
ORDER BY r.role_key, p.perm_key;
