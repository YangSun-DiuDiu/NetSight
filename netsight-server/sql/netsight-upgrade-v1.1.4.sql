-- =====================================================================
-- NetSight(NAMS) V1.1.4 增量SQL —— 第5周：故障工单闭环 + 备品备件库
-- 内容：6 张新表（work_order/repairer/work_order_record/work_order_part/spare_part/spare_part_record）
--       + 2 条派单通知模板 + 权限 76-93 + super_admin(role_id=1) 绑定 18 条
-- 执行方式：mysql -usadmin -pChinaunicom@1358 netsight < netsight-upgrade-v1.1.4.sql
-- 约束：INSERT 一律显式 id，避免 auto_increment 错位
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 维修人员库（repairer）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `repairer` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`    BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户',
  `repairer_no`  VARCHAR(32)  NOT NULL COMMENT '维修人员编号 REP+时间戳+随机',
  `name`         VARCHAR(64)  NOT NULL COMMENT '姓名',
  `phone`        VARCHAR(20)  DEFAULT NULL COMMENT '手机号（短信通知）',
  `openid`       VARCHAR(64)  DEFAULT NULL COMMENT '微信公众号OpenID',
  `region`       VARCHAR(128) DEFAULT NULL COMMENT '负责区域（楼宇/园区）',
  `device_types` VARCHAR(255) DEFAULT NULL COMMENT '负责设备类型 JSON数组 [network,camera]',
  `skills`       VARCHAR(255) DEFAULT NULL COMMENT '技能标签（逗号分隔）',
  `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1在岗 0休假 2离职',
  `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_repairer_no` (`tenant_id`, `repairer_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='维修人员库';

-- ---------------------------------------------------------------------
-- 2. 故障工单表（work_order）：告警/手动触发，管理员审核报修派单，维修闭环
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `work_order` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`         BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户',
  `order_no`          VARCHAR(32)  NOT NULL COMMENT '工单编号 WO+时间戳+随机',
  `source_type`       VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT '来源 event告警/manual手动',
  `event_id`          BIGINT       DEFAULT NULL COMMENT '关联事件ID',
  `device_code`       VARCHAR(64)  DEFAULT NULL COMMENT '设备编码',
  `device_name`       VARCHAR(128) DEFAULT NULL COMMENT '设备名称',
  `device_ip`         VARCHAR(64)  DEFAULT NULL COMMENT '设备IP',
  `device_type`       VARCHAR(32)  DEFAULT NULL COMMENT '设备类型 network/camera/nvr/door_controller',
  `device_location`   VARCHAR(255) DEFAULT NULL COMMENT '部署位置',
  `fault_type`        VARCHAR(32)  NOT NULL DEFAULT 'manual' COMMENT '故障类型 offline离线/line_abnormal链路/manual手动',
  `severity`          VARCHAR(16)  NOT NULL DEFAULT 'info' COMMENT '级别 critical/warning/info',
  `description`       VARCHAR(500) DEFAULT NULL COMMENT '故障描述',
  `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1已派单 2维修中 3已完成 4已关闭 5已自动恢复',
  `repairer_id`       BIGINT       DEFAULT NULL COMMENT '维修人员ID',
  `repairer_name`     VARCHAR(64)  DEFAULT NULL COMMENT '维修人员姓名（快照）',
  `dispatch_time`     DATETIME     DEFAULT NULL COMMENT '派单时间',
  `repair_start_time` DATETIME     DEFAULT NULL COMMENT '维修开始时间',
  `repair_end_time`   DATETIME     DEFAULT NULL COMMENT '维修完成时间',
  `repair_result`     VARCHAR(500) DEFAULT NULL COMMENT '维修结果',
  `remark`            VARCHAR(500) DEFAULT NULL COMMENT '补充说明',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`tenant_id`, `order_no`),
  KEY `idx_order_status` (`tenant_id`, `status`),
  KEY `idx_order_device` (`tenant_id`, `device_code`, `fault_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='故障工单表';

-- ---------------------------------------------------------------------
-- 3. 工单处理记录（work_order_record）：生成/派单/接单/完成/关闭时间线
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `work_order_record` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`  BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户',
  `order_id`   BIGINT       NOT NULL COMMENT '工单ID',
  `action`     VARCHAR(32)  NOT NULL COMMENT '动作 auto_create/dispatch/repair_start/complete/close/recover',
  `operator`   VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
  `content`    VARCHAR(500) DEFAULT NULL COMMENT '处理内容',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_record_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='工单处理记录';

-- ---------------------------------------------------------------------
-- 4. 工单备件关联（work_order_part）：每张工单可关联多个备件领用
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `work_order_part` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`  BIGINT      NOT NULL DEFAULT 1 COMMENT '所属租户',
  `order_id`   BIGINT      NOT NULL COMMENT '工单ID',
  `part_id`    BIGINT      NOT NULL COMMENT '备件ID',
  `part_no`    VARCHAR(32) DEFAULT NULL COMMENT '备件编号（快照）',
  `part_name`  VARCHAR(128) DEFAULT NULL COMMENT '备件名称（类型+品牌+型号）',
  `quantity`   INT         NOT NULL DEFAULT 1 COMMENT '领用数量',
  `unit`       VARCHAR(16) DEFAULT NULL COMMENT '单位',
  `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_op_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='工单备件关联';

-- ---------------------------------------------------------------------
-- 5. 备品备件库（spare_part）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `spare_part` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户',
  `part_no`     VARCHAR(32)  NOT NULL COMMENT '备件编号 SP+时间戳+随机',
  `part_type`   VARCHAR(64)  NOT NULL COMMENT '类型 交换机/路由器/摄像头/NVR/门禁控制器/光纤模块/电源/网线等',
  `brand`       VARCHAR(64)  DEFAULT NULL COMMENT '品牌',
  `model`       VARCHAR(128) DEFAULT NULL COMMENT '型号',
  `serial_no`   VARCHAR(64)  DEFAULT NULL COMMENT '序列号SN，唯一可追溯',
  `quantity`    INT          NOT NULL DEFAULT 0 COMMENT '当前库存数量',
  `unit`        VARCHAR(16)  DEFAULT '台' COMMENT '单位 台/个/条/块',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'new' COMMENT '状态 new全新/repairing返修中/repaired已修复',
  `location`    VARCHAR(128) DEFAULT NULL COMMENT '存放位置',
  `safe_stock`  INT          NOT NULL DEFAULT 0 COMMENT '安全库存阈值（低于标红预警）',
  `in_time`     DATE         DEFAULT NULL COMMENT '入库时间',
  `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_part_no` (`tenant_id`, `part_no`),
  KEY `idx_part_type` (`tenant_id`, `part_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='备品备件库';

-- ---------------------------------------------------------------------
-- 6. 备件出入库记录（spare_part_record）：入库/领用出库/返修入库/报废，关联工单
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `spare_part_record` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 1 COMMENT '所属租户',
  `part_id`     BIGINT       NOT NULL COMMENT '备件ID',
  `part_no`     VARCHAR(32)  DEFAULT NULL COMMENT '备件编号（快照）',
  `part_name`   VARCHAR(128) DEFAULT NULL COMMENT '备件名称（快照）',
  `record_type` VARCHAR(16)  NOT NULL COMMENT '类型 in入库/out领用出库/return_in返修入库/repair返修/scrap报废/adjust调整',
  `quantity`    INT          NOT NULL DEFAULT 0 COMMENT '数量（out为负数扣减）',
  `order_id`    BIGINT       DEFAULT NULL COMMENT '关联工单ID（领用出库必填）',
  `order_no`    VARCHAR(32)  DEFAULT NULL COMMENT '工单编号（快照）',
  `operator`    VARCHAR(64)  DEFAULT NULL COMMENT '操作人',
  `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `del_flag`    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常 2删除',
  PRIMARY KEY (`id`),
  KEY `idx_record_part` (`part_id`),
  KEY `idx_record_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='备件出入库记录';

-- ---------------------------------------------------------------------
-- 7. 派单通知模板（tpl_order_dispatch 短信+公众号）
-- ---------------------------------------------------------------------
INSERT INTO `notification_template` (`id`, `tenant_id`, `template_code`, `template_name`, `channel_type`, `content`, `enabled`, `create_time`, `update_time`, `del_flag`) VALUES
(7, 1, 'tpl_order_dispatch', '工单派单通知', 'sms', '【运维告警】工单{{orderNo}}：设备{{deviceName}}({{deviceIp}}){{faultDesc}}，请{{repairerName}}尽快上门处理，位置：{{deviceLocation}}', 1, NOW(), NOW(), 0),
(8, 1, 'tpl_order_dispatch', '工单派单通知', 'wechat', '⚠️ 新工单派发 工单号：{{orderNo}} 设备：{{deviceName}}（{{deviceIp}}） 类型：{{deviceType}} 位置：{{deviceLocation}} 故障：{{faultDesc}} 请{{repairerName}}尽快处理并回填维修结果。', 1, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 8. 菜单/权限（显式 id）
--    76 运维工单顶级；77-78 子菜单；79-84 按钮
--    85 备品备件顶级；86-87 子菜单；88-93 按钮
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(76, 0,  '运维工单', 'workorder:menu',   'menu',   2, '/workorder', 'Layout',               'tickets',      NOW(), NOW(), 0),
(77, 76, '工单管理', 'workorder:order:menu','menu',1, 'order',    'workorder/order/index',  'list',        NOW(), NOW(), 0),
(78, 76, '维修人员', 'workorder:repairer:menu','menu',2, 'repairer','workorder/repairer/index','peoples',    NOW(), NOW(), 0),
(79, 77, '工单查询', 'workorder:order:list',  'button', 1, '', '', '', NOW(), NOW(), 0),
(80, 77, '工单新增', 'workorder:order:add',   'button', 2, '', '', '', NOW(), NOW(), 0),
(81, 77, '工单修改', 'workorder:order:edit',  'button', 3, '', '', '', NOW(), NOW(), 0),
(82, 77, '工单删除', 'workorder:order:remove', 'button', 4, '', '', '', NOW(), NOW(), 0),
(83, 77, '报修派单', 'workorder:order:dispatch','button',5, '', '', '', NOW(), NOW(), 0),
(84, 77, '完工关闭', 'workorder:order:complete','button',6, '', '', '', NOW(), NOW(), 0),
(85, 78, '人员查询', 'workorder:repairer:list','button',1, '', '', '', NOW(), NOW(), 0),
(86, 78, '人员新增', 'workorder:repairer:add', 'button', 2, '', '', '', NOW(), NOW(), 0),
(87, 78, '人员修改', 'workorder:repairer:edit','button', 3, '', '', '', NOW(), NOW(), 0),
(88, 78, '人员删除', 'workorder:repairer:remove','button',4, '', '', '', NOW(), NOW(), 0),
(89, 0,  '备品备件', 'spare:menu',      'menu',   3, '/spare',   'Layout',               'box',          NOW(), NOW(), 0),
(90, 89, '备件管理', 'spare:part:menu', 'menu',   1, 'part',     'spare/part/index',      'component',    NOW(), NOW(), 0),
(91, 89, '出入库记录', 'spare:record:menu','menu',2, 'record',   'spare/record/index',    'log',          NOW(), NOW(), 0),
(92, 90, '备件查询', 'spare:part:list', 'button', 1, '', '', '', NOW(), NOW(), 0),
(93, 90, '备件新增', 'spare:part:add',  'button', 2, '', '', '', NOW(), NOW(), 0),
(94, 90, '备件修改', 'spare:part:edit', 'button', 3, '', '', '', NOW(), NOW(), 0),
(95, 90, '备件删除', 'spare:part:remove','button',4, '', '', '', NOW(), NOW(), 0),
(96, 90, '出入库操作', 'spare:part:stock','button',5, '', '', '', NOW(), NOW(), 0),
(97, 91, '记录查询', 'spare:record:list','button',1, '', '', '', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 9. super_admin（role_id=1）绑定新增权限（76-97）
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id >= 76 AND permission_id <= 97;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 76), (1, 77), (1, 78),
(1, 79), (1, 80), (1, 81), (1, 82), (1, 83), (1, 84),
(1, 85), (1, 86), (1, 87), (1, 88),
(1, 89), (1, 90), (1, 91),
(1, 92), (1, 93), (1, 94), (1, 95), (1, 96), (1, 97);
