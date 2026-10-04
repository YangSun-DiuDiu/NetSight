-- =====================================================================
-- NetSight：门禁管理功能移除（2026-10-02 用户要求删除门禁系统全部功能）
-- 回退 netsight-upgrade-v1.1.20.sql：DROP 4 张 door 表 + 清理权限 149-154 及角色绑定
-- =====================================================================
DROP TABLE IF EXISTS `door_authorization`;
DROP TABLE IF EXISTS `door_device_config`;
DROP TABLE IF EXISTS `door_person`;
DROP TABLE IF EXISTS `door_point`;

DELETE FROM `sys_role_permission` WHERE permission_id BETWEEN 149 AND 154;
DELETE FROM `sys_permission` WHERE id BETWEEN 149 AND 154;

-- 校验
SELECT COUNT(*) AS remaining_door_tables FROM information_schema.tables WHERE table_schema='netsight' AND table_name LIKE 'door%';
SELECT COUNT(*) AS remaining_perm FROM sys_permission WHERE id BETWEEN 149 AND 154;
