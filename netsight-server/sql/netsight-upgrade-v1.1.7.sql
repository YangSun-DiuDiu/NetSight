-- =============================================================
-- NetSight V1.1.7：删除独立"网络拓扑"页面菜单与按钮权限
-- 说明：
--  1. 删除菜单 100（device:topology:menu 网络拓扑页 /device/topology）与按钮 101（device:topology:view）
--  2. 保留：设备上下级关系（device_topology 表 + 设备页拓扑维护 1主2备）
--  3. 保留：GET /device/topology/tree 输出 JSON 拓扑树能力（角色授权，不受影响）
--  4. 后期拓扑图由第三方工具导入 JSON 生成，本系统不再内置拓扑展示页
-- =============================================================

-- 1. 删除角色-权限关联（先删关联，避免孤儿数据）
DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission
    WHERE perm_key IN ('device:topology:menu', 'device:topology:view')
);

-- 2. 删除权限记录（菜单 + 按钮）
DELETE FROM sys_permission
WHERE perm_key IN ('device:topology:menu', 'device:topology:view');
