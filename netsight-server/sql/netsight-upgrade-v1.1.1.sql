-- =====================================================
-- NetSight V1.1.1 系统管理模块增量脚本
-- 功能：菜单/按钮权限数据初始化、角色-权限关联
-- 执行库：netsight
-- =====================================================
USE `netsight`;

-- -----------------------------------------------------
-- 1. 权限菜单数据（sys_permission）
--    结构：系统管理(menu) -> 租户/用户/角色管理(menu) -> 各按钮权限(button)
-- -----------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`) VALUES
-- 顶级菜单：系统管理
(1,   0,    '系统管理', 'system',            'menu',   1),
-- 系统管理 -> 子菜单
(10,  1,    '租户管理', 'system:tenant:menu', 'menu',   1),
(20,  1,    '用户管理', 'system:user:menu',   'menu',   2),
(30,  1,    '角色管理', 'system:role:menu',   'menu',   3),
-- 租户管理按钮
(11,  10,   '租户查询', 'system:tenant:list',   'button', 1),
(12,  10,   '租户新增', 'system:tenant:add',    'button', 2),
(13,  10,   '租户修改', 'system:tenant:edit',   'button', 3),
(14,  10,   '租户删除', 'system:tenant:remove', 'button', 4),
-- 用户管理按钮
(21,  20,   '用户查询', 'system:user:list',      'button', 1),
(22,  20,   '用户新增', 'system:user:add',       'button', 2),
(23,  20,   '用户修改', 'system:user:edit',      'button', 3),
(24,  20,   '用户删除', 'system:user:remove',    'button', 4),
(25,  20,   '重置密码', 'system:user:resetPwd',  'button', 5),
(26,  20,   '分配角色', 'system:user:assignRole','button', 6),
-- 角色管理按钮
(31,  30,   '角色查询', 'system:role:list',       'button', 1),
(32,  30,   '角色新增', 'system:role:add',        'button', 2),
(33,  30,   '角色修改', 'system:role:edit',       'button', 3),
(34,  30,   '角色删除', 'system:role:remove',     'button', 4),
(35,  30,   '分配权限', 'system:role:assignPerm', 'button', 5);

-- -----------------------------------------------------
-- 2. 超级管理员绑定全部权限（sys_role_permission）
-- -----------------------------------------------------
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, `id` FROM `sys_permission` WHERE `del_flag` = 0;
