-- 回滚：恢复 tenant_admin / ops 的流程定义与流程设计器菜单
-- 日期：2026-11-20
-- 适用：执行 workflow-superadmin-only-20261120.sql 后需要恢复原状
-- 说明：原状态为 tenant_admin(2)、ops(3) 均拥有权限 156、157；
--       数据库完整备份见 115 服务器
--       /opt/nams-server/backups/pre-workflow-superadmin-20261120-065945.sql

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.perm_key IN ('workflow:definition:menu', 'workflow:designer:menu')
WHERE r.role_key IN ('tenant_admin', 'ops');
