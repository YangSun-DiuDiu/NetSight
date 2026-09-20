-- =====================================================================
-- NetSight V1.2 统一待办（demo 功能 4/5）
-- 增量 SQL v1.1.15
-- 说明：统一待办为"聚合中心"，不新建业务表（聚合查询 work_order / inspection_task）
-- 权限：131-134（131 统一待办顶级 / 132 待办列表 / 133-134 按钮）
-- 角色：role1/2/3/4 全绑（待办中心人人可用，无增删改）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 菜单/权限（显式 id，从 131 开始；icon=el-icon-s-order 已确认存在）
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(131, 0,   '统一待办', 'todo:menu',       'menu',   2, '/todo', 'Layout',       'el-icon-s-order', NOW(), NOW(), 0),
(132, 131, '待办列表', 'todo:list:menu',  'menu',   1, 'list',  'todo/index',   'documentation',   NOW(), NOW(), 0),
(133, 132, '待办查询', 'todo:list',       'button', 1, '', '', '', NOW(), NOW(), 0),
(134, 132, '待办处理', 'todo:view',       'button', 2, '', '', '', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 2. 角色绑定（幂等）：role1/2/3/4 全绑 131-134
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id BETWEEN 131 AND 134;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 131), (1, 132), (1, 133), (1, 134);
DELETE FROM sys_role_permission WHERE role_id = 2 AND permission_id BETWEEN 131 AND 134;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2, 131), (2, 132), (2, 133), (2, 134);
DELETE FROM sys_role_permission WHERE role_id = 3 AND permission_id BETWEEN 131 AND 134;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3, 131), (3, 132), (3, 133), (3, 134);
DELETE FROM sys_role_permission WHERE role_id = 4 AND permission_id BETWEEN 131 AND 134;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(4, 131), (4, 132), (4, 133), (4, 134);

-- ---------------------------------------------------------------------
-- 3. 校验查询
-- ---------------------------------------------------------------------
SELECT id, perm_name, perm_key, icon FROM sys_permission WHERE id BETWEEN 131 AND 134 AND del_flag = 0;
SELECT role_id, COUNT(*) cnt FROM sys_role_permission WHERE permission_id BETWEEN 131 AND 134 GROUP BY role_id ORDER BY role_id;
