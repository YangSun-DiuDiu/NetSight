-- =====================================================================
-- NetSight（NAMS）V1.1.13 增量升级脚本（demo 功能 2/5：点检巡检）
-- 点检项库 + 点检计划（周期自动生成任务）+ 点检任务执行 + 点检记录
-- 适用：192.168.1.55 MySQL（库 netsight）
-- 注意：权限/菜单 INSERT 一律显式指定 id（从 110 开始）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 点检项库（点检模板：检查内容 + 标准）
--    result_type: check 勾选（正常/异常）/ text 填写数值文本
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID（多租户隔离）',
    item_name VARCHAR(128) NOT NULL COMMENT '点检项名称',
    check_content VARCHAR(255) DEFAULT NULL COMMENT '检查内容',
    check_standard VARCHAR(255) DEFAULT NULL COMMENT '检查标准/达标要求',
    result_type VARCHAR(16) NOT NULL DEFAULT 'check' COMMENT '结果类型：check 勾选 / text 文本填写',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序（越小越靠前）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    INDEX idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检项库';

-- ---------------------------------------------------------------------
-- 2. 点检计划（按周期为目标生成点检任务）
--    cycle_type : daily 每日 / weekly 每周一 / monthly 每月1日
--    target_type: device 指定设备 / device_type 按设备类型
--    target_ids : JSON 数组（设备ID列表 或 设备类型列表）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    plan_name VARCHAR(128) NOT NULL COMMENT '计划名称',
    cycle_type VARCHAR(16) NOT NULL DEFAULT 'daily' COMMENT '周期：daily 每日 / weekly 每周一 / monthly 每月1日',
    target_type VARCHAR(16) NOT NULL DEFAULT 'device' COMMENT '对象：device 指定设备 / device_type 按类型',
    target_ids VARCHAR(1024) DEFAULT NULL COMMENT '目标ID列表（JSON数组）',
    assignee_id BIGINT DEFAULT NULL COMMENT '默认执行人用户ID',
    assignee_name VARCHAR(64) DEFAULT NULL COMMENT '执行人姓名（快照）',
    start_date DATE DEFAULT NULL COMMENT '生效开始日期',
    end_date DATE DEFAULT NULL COMMENT '生效结束日期（空=长期）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    INDEX idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检计划';

-- ---------------------------------------------------------------------
-- 3. 点检任务（计划按周期生成；可手动创建）
--    status: 0 待执行 / 1 执行中 / 2 已完成 / 3 已逾期
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_no VARCHAR(32) NOT NULL COMMENT '任务编号（INSP+时间戳）',
    plan_id BIGINT DEFAULT NULL COMMENT '来源计划ID（手动创建为空）',
    plan_name VARCHAR(128) DEFAULT NULL COMMENT '计划名称（快照）',
    target_id BIGINT NOT NULL COMMENT '点检目标ID（设备ID或类型）',
    target_name VARCHAR(128) NOT NULL COMMENT '目标名称（快照）',
    target_type VARCHAR(32) DEFAULT 'device' COMMENT '目标类型：device / device_type',
    target_location VARCHAR(255) DEFAULT NULL COMMENT '目标位置（快照）',
    assignee_id BIGINT DEFAULT NULL COMMENT '执行人用户ID',
    assignee_name VARCHAR(64) DEFAULT NULL COMMENT '执行人姓名（快照）',
    plan_date DATE NOT NULL COMMENT '计划执行日期',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待执行 / 1 执行中 / 2 已完成 / 3 已逾期',
    abnormal_count INT NOT NULL DEFAULT 0 COMMENT '异常项数量',
    finish_time DATETIME DEFAULT NULL COMMENT '完成时间',
    remark VARCHAR(255) DEFAULT NULL COMMENT '任务备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_plan_target_date (tenant_id, plan_id, target_id, plan_date),
    INDEX idx_assignee_status (assignee_id, status),
    INDEX idx_plan_date (plan_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检任务';

-- ---------------------------------------------------------------------
-- 4. 点检记录（任务下逐项检查结果；异常可联动报修工单）
--    result: 0 正常 / 1 异常；text_result 供 text 型填写
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '点检任务ID',
    item_id BIGINT DEFAULT NULL COMMENT '点检项ID（快照源）',
    item_name VARCHAR(128) NOT NULL COMMENT '点检项名称（快照）',
    check_content VARCHAR(255) DEFAULT NULL COMMENT '检查内容（快照）',
    check_standard VARCHAR(255) DEFAULT NULL COMMENT '检查标准（快照）',
    result_type VARCHAR(16) NOT NULL DEFAULT 'check' COMMENT '结果类型',
    result TINYINT NOT NULL DEFAULT 0 COMMENT '结果：0 正常 / 1 异常',
    text_result VARCHAR(255) DEFAULT NULL COMMENT '文本结果（text 型）',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注',
    order_id BIGINT DEFAULT NULL COMMENT '异常报修关联工单ID',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户ID',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '操作人姓名（快照）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    INDEX idx_task (task_id),
    INDEX idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点检记录';

-- ---------------------------------------------------------------------
-- 5. 菜单/权限（显式 id，从 110 开始）
--    110 点检巡检顶级（icon=el-icon-date，Element 内置已确认存在）
--    111 点检任务（path='task'）112 点检计划（path='plan'）113 点检项库（path='item'）
--    114-123 按钮权限
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(110, 0,   '点检巡检', 'inspection:menu',     'menu',   1, '/inspection', 'Layout',             'el-icon-date',   NOW(), NOW(), 0),
(111, 110, '点检任务', 'inspection:task:menu', 'menu',   1, 'task',        'inspection/task/index', 'form',        NOW(), NOW(), 0),
(112, 110, '点检计划', 'inspection:plan:menu', 'menu',   2, 'plan',        'inspection/plan/index', 'list',         NOW(), NOW(), 0),
(113, 110, '点检项库', 'inspection:item:menu', 'menu',   3, 'item',        'inspection/item/index', 'table',        NOW(), NOW(), 0),
(114, 111, '任务查询', 'inspection:task:list',    'button', 1, '', '', '', NOW(), NOW(), 0),
(115, 111, '任务执行', 'inspection:task:execute', 'button', 2, '', '', '', NOW(), NOW(), 0),
(116, 112, '计划查询', 'inspection:plan:list',    'button', 1, '', '', '', NOW(), NOW(), 0),
(117, 112, '计划新增', 'inspection:plan:add',     'button', 2, '', '', '', NOW(), NOW(), 0),
(118, 112, '计划修改', 'inspection:plan:edit',    'button', 3, '', '', '', NOW(), NOW(), 0),
(119, 112, '计划删除', 'inspection:plan:remove',  'button', 4, '', '', '', NOW(), NOW(), 0),
(120, 113, '项库查询', 'inspection:item:list',    'button', 1, '', '', '', NOW(), NOW(), 0),
(121, 113, '项库新增', 'inspection:item:add',     'button', 2, '', '', '', NOW(), NOW(), 0),
(122, 113, '项库修改', 'inspection:item:edit',    'button', 3, '', '', '', NOW(), NOW(), 0),
(123, 113, '项库删除', 'inspection:item:remove',  'button', 4, '', '', '', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 6. super_admin + tenant_admin 绑定 110-123（幂等）
--    运维/维修人员无管理菜单，通过"点检任务"执行本租户任务
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id >= 110 AND permission_id <= 123;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 110), (1, 111), (1, 112), (1, 113), (1, 114), (1, 115), (1, 116), (1, 117), (1, 118), (1, 119), (1, 120), (1, 121), (1, 122), (1, 123);
DELETE FROM sys_role_permission WHERE role_id = 2 AND permission_id >= 110 AND permission_id <= 123;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2, 110), (2, 111), (2, 112), (2, 113), (2, 114), (2, 115), (2, 116), (2, 117), (2, 118), (2, 119), (2, 120), (2, 121), (2, 122), (2, 123);

-- ---------------------------------------------------------------------
-- 7. 演示数据（租户1：点检项 4 条 + 计划 1 条 + 当天任务 1 条）
-- ---------------------------------------------------------------------
INSERT INTO inspection_item (id, tenant_id, item_name, check_content, check_standard, result_type, status, sort, del_flag) VALUES
(1, 1, '设备运行指示灯', '检查设备面板运行指示灯是否正常闪烁', '指示灯正常亮起/闪烁，无报警红灯', 'check', 1, 1, 0),
(2, 1, '设备温度', '检查设备表面温度是否过热', '表面温度正常，无烫手现象', 'check', 1, 2, 0),
(3, 1, '电源连接', '检查电源线、网线连接是否牢固', '线缆连接牢固，无松动、无破损', 'check', 1, 3, 0),
(4, 1, '环境整洁度', '检查设备机柜/安装位环境是否整洁', '无积尘、无杂物堆积、通风良好', 'check', 1, 4, 0);

-- 计划：核心交换机每日点检（目标=设备类型 network，执行人 admin）
INSERT INTO inspection_plan (id, tenant_id, plan_name, cycle_type, target_type, target_ids, assignee_id, assignee_name, start_date, end_date, status, remark, del_flag) VALUES
(1, 1, '网络设备每日点检', 'daily', 'device_type', '["network"]', 1, 'admin', NULL, NULL, 1, '对网络类设备每日执行例行点检', 0);

-- 当天任务（demo 设备：id=1 核心交换机）
INSERT INTO inspection_task (id, tenant_id, task_no, plan_id, plan_name, target_id, target_name, target_type, target_location, assignee_id, assignee_name, plan_date, status, abnormal_count, finish_time, remark, del_flag) VALUES
(1, 1, CONCAT('INSP', DATE_FORMAT(NOW(), '%Y%m%d'), '01'), 1, '网络设备每日点检', 1, '核心交换机', 'device', '机房A-01机柜', 1, 'admin', CURDATE(), 0, 0, NULL, NULL, 0);

-- ---------------------------------------------------------------------
-- 8. 校验
-- ---------------------------------------------------------------------
-- 应返回 14 行权限 + 4 条点检项 + 1 条计划 + 1 条任务
SELECT id, perm_name, perm_key, icon FROM sys_permission WHERE id BETWEEN 110 AND 123 AND del_flag = 0;
SELECT id, item_name, result_type FROM inspection_item WHERE del_flag = 0;
SELECT id, plan_name, cycle_type, status FROM inspection_plan WHERE del_flag = 0;
SELECT id, task_no, target_name, status FROM inspection_task WHERE del_flag = 0;

-- ============================================================
-- ops(3)/repairer(4) 绑定点检任务菜单：可查看并执行本租户点检任务（不给 plan/item 管理权限）
-- ============================================================
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE perm_key IN ('inspection:task:menu','inspection:task:list','inspection:task:execute')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=3 AND rp.permission_id=sys_permission.id);
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE perm_key IN ('inspection:task:menu','inspection:task:list','inspection:task:execute')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=4 AND rp.permission_id=sys_permission.id);
-- ops/repairer 补充绑定点检巡检顶级菜单（此前只绑子菜单，动态路由树缺父级不显示）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission WHERE perm_key='inspection:menu'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=3 AND rp.permission_id=sys_permission.id);
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE perm_key='inspection:menu'
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=4 AND rp.permission_id=sys_permission.id);
