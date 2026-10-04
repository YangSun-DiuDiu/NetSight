-- =====================================================================
-- NetSight V1.1.20：门禁管理模块（阶段1a 后端数据模型 + 权限）
-- 表：door_point 门禁点位 / door_person 门禁人员 / door_authorization 授权记录 / door_device_config 设备连接配置
-- 权限：149-155（149 门禁管理顶级菜单挂设备资产 id=40 下）
-- 角色：role1/2 全量；role3(ops)=149+150+152（可查看+开门，不可配置/人员/授权）；role4 不绑
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 数据表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `door_point` (
  `id`          BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id`   BIGINT NOT NULL COMMENT '所属租户ID（多租户隔离）',
  `device_id`   BIGINT NOT NULL COMMENT '关联 device 表主键',
  `point_name`  VARCHAR(64) NOT NULL COMMENT '门名称',
  `channel_no`  VARCHAR(16) NOT NULL COMMENT '门禁通道号（设备侧）',
  `door_mode`   VARCHAR(16) DEFAULT 'normal' COMMENT '门模式 normal/open/close',
  `remark`      VARCHAR(255) COMMENT '备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_point` (`tenant_id`, `device_id`, `channel_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门禁点位（门）';

CREATE TABLE IF NOT EXISTS `door_person` (
  `id`          BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id`   BIGINT NOT NULL COMMENT '所属租户ID',
  `person_name` VARCHAR(64) NOT NULL COMMENT '人员姓名',
  `person_no`   VARCHAR(32) COMMENT '工号/人员编号（同步设备端UserID）',
  `card_no`     VARCHAR(32) COMMENT '卡号',
  `phone`       VARCHAR(20) COMMENT '手机号',
  `remark`      VARCHAR(255) COMMENT '备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_person_no` (`tenant_id`, `person_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门禁人员';

CREATE TABLE IF NOT EXISTS `door_authorization` (
  `id`             BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id`      BIGINT NOT NULL COMMENT '所属租户ID',
  `person_id`      BIGINT NOT NULL COMMENT 'door_person.id',
  `point_id`       BIGINT NOT NULL COMMENT 'door_point.id',
  `time_zone`      VARCHAR(64) COMMENT '时间段（如 00:00-23:59 全天统一）',
  `push_status`    TINYINT NOT NULL DEFAULT 0 COMMENT '下发状态 0未下发 1已下发 2下发失败 3已清除',
  `last_push_time` DATETIME COMMENT '最近一次下发时间',
  `create_time`    DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time`    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`       TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_auth` (`tenant_id`, `person_id`, `point_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门禁授权记录';

CREATE TABLE IF NOT EXISTS `door_device_config` (
  `id`          BIGINT PRIMARY KEY AUTO_INCREMENT,
  `tenant_id`   BIGINT NOT NULL COMMENT '所属租户ID',
  `device_id`   BIGINT NOT NULL COMMENT '关联 device 表主键',
  `driver_type` VARCHAR(32) NOT NULL COMMENT '驱动类型 dahua_asi/zkbio_inbio',
  `http_port`   INT NOT NULL DEFAULT 80 COMMENT 'HTTP端口',
  `username`    VARCHAR(64) COMMENT '设备登录账号',
  `password`    VARCHAR(256) COMMENT '设备登录密码（AES-256-GCM加密）',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag`    TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY `uk_cfg` (`tenant_id`, `device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门禁设备连接配置';

-- ---------------------------------------------------------------------
-- 2. 菜单/权限（显式 id 149-155，门禁管理挂设备资产 id=40 下）
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(149, 40,  '门禁管理', 'door:menu',     'menu',   3, 'door', 'door/index', 'lock',       NOW(), NOW(), 0),
(150, 149, '门禁查询', 'door:list',     'button', 1, '', '', '', NOW(), NOW(), 0),
(151, 149, '门禁配置', 'door:config',   'button', 2, '', '', '', NOW(), NOW(), 0),
(152, 149, '远程开门', 'door:open',     'button', 3, '', '', '', NOW(), NOW(), 0),
(153, 149, '人员管理', 'door:person',   'button', 4, '', '', '', NOW(), NOW(), 0),
(154, 149, '权限下发', 'door:authorize','button', 5, '', '', '', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 3. 角色绑定（幂等）
-- role1 super_admin：全量 149-154
-- role2 tenant_admin：全量 149-154
-- role3 ops：149(菜单) + 150(查询) + 152(开门)，无配置/人员/授权
-- role4 repairer：不绑定（无门禁权限）
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id BETWEEN 149 AND 154;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 149), (1, 150), (1, 151), (1, 152), (1, 153), (1, 154);

DELETE FROM sys_role_permission WHERE role_id = 2 AND permission_id BETWEEN 149 AND 154;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(2, 149), (2, 150), (2, 151), (2, 152), (2, 153), (2, 154);

DELETE FROM sys_role_permission WHERE role_id = 3 AND permission_id IN (149, 150, 152);
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(3, 149), (3, 150), (3, 152);

-- ---------------------------------------------------------------------
-- 4. 校验查询
-- ---------------------------------------------------------------------
SELECT id, perm_name, perm_key, perm_type, icon FROM sys_permission WHERE id BETWEEN 149 AND 154 AND del_flag = 0;
SELECT role_id, COUNT(*) cnt FROM sys_role_permission WHERE permission_id BETWEEN 149 AND 154 GROUP BY role_id ORDER BY role_id;
