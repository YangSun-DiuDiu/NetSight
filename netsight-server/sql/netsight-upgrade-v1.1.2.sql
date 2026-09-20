-- =============================================================
-- NetSight V1.1.2 增量脚本：第 3 周 边缘网关 + 设备资产 + 拓扑
-- 1. 新增 3 张业务表：edge_gateway / device / device_topology
-- 2. sys_permission 增加 path/component/icon 列（动态路由用）
-- 3. 新增设备资产、网关管理菜单与按钮权限，绑定 super_admin
-- =============================================================

-- 1. 边缘网关表（本地监控栈，云端注册与状态管理）
CREATE TABLE IF NOT EXISTS `edge_gateway` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `gateway_code`       VARCHAR(32)  NOT NULL COMMENT '网关唯一编码',
  `gateway_name`       VARCHAR(64)  NOT NULL COMMENT '网关名称',
  `location`           VARCHAR(256) DEFAULT NULL COMMENT '部署位置',
  `tenant_id`          BIGINT       NOT NULL COMMENT '所属租户ID',
  `gateway_token`      VARCHAR(64)  NOT NULL COMMENT '接入Token（网关上报鉴权）',
  `link_type`          VARCHAR(16)  DEFAULT 'wired' COMMENT '上行链路：wired/wifi/4g5g',
  `ip_address`         VARCHAR(64)  DEFAULT NULL COMMENT '最近上报IP',
  `online_status`      TINYINT      DEFAULT 0 COMMENT '在线状态：0离线/1在线',
  `last_heartbeat_time` DATETIME    DEFAULT NULL COMMENT '最近心跳时间',
  `status`             TINYINT      DEFAULT 1 COMMENT '启用状态：1启用/0禁用（禁用后上报不接收）',
  `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `del_flag`           TINYINT      DEFAULT 0 COMMENT '逻辑删除：0正常/2已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gateway_code` (`gateway_code`),
  KEY `idx_gateway_tenant` (`tenant_id`),
  KEY `idx_gateway_online` (`online_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='边缘网关表';

-- 2. 设备资产表（网络/视频/门禁统一纳管）
CREATE TABLE IF NOT EXISTS `device` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_code`        VARCHAR(64)  NOT NULL COMMENT '设备唯一编码（业务主键）',
  `device_name`        VARCHAR(128) NOT NULL COMMENT '设备名称',
  `device_type`        VARCHAR(32)  NOT NULL COMMENT '设备类型：network/camera/nvr/door_controller',
  `brand`              VARCHAR(64)  DEFAULT NULL COMMENT '品牌',
  `model`              VARCHAR(128) DEFAULT NULL COMMENT '型号',
  `ip_address`         VARCHAR(64)  DEFAULT NULL COMMENT '管理IP',
  `location`           VARCHAR(256) DEFAULT NULL COMMENT '部署位置',
  `gateway_id`         BIGINT       DEFAULT NULL COMMENT '归属网关ID',
  `tenant_id`          BIGINT       NOT NULL COMMENT '所属租户ID',
  `status`             TINYINT      DEFAULT 0 COMMENT '运行状态：1在线/0离线/2异常（device_up）',
  `line_status`        TINYINT      DEFAULT 0 COMMENT '外线状态：0正常/1异常（device_line_abnormal）',
  `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  `update_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `del_flag`           TINYINT      DEFAULT 0 COMMENT '逻辑删除：0正常/2已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_code` (`device_code`),
  KEY `idx_device_tenant` (`tenant_id`),
  KEY `idx_device_gateway` (`gateway_id`),
  KEY `idx_device_type` (`device_type`),
  KEY `idx_device_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备资产表';

-- 3. 设备拓扑关系表（一台设备最多 1 主 + 2 备 上级）
CREATE TABLE IF NOT EXISTS `device_topology` (
  `id`                 BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`          BIGINT   NOT NULL COMMENT '下级设备ID',
  `parent_main_id`     BIGINT   NOT NULL COMMENT '主上级设备ID（必填）',
  `parent_backup1_id`  BIGINT   DEFAULT NULL COMMENT '备路1上级ID（选填）',
  `parent_backup2_id`  BIGINT   DEFAULT NULL COMMENT '备路2上级ID（选填，最多2路备份）',
  `tenant_id`          BIGINT   NOT NULL COMMENT '所属租户ID',
  `create_time`        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `del_flag`           TINYINT  DEFAULT 0 COMMENT '逻辑删除：0正常/2已删',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topology_device` (`device_id`),
  KEY `idx_topology_parent_main` (`parent_main_id`),
  KEY `idx_topology_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备拓扑关系表';

-- 4. sys_permission 增加动态路由所需字段
ALTER TABLE `sys_permission`
  ADD COLUMN `path` VARCHAR(128) DEFAULT '' COMMENT '路由路径' AFTER `sort`,
  ADD COLUMN `component` VARCHAR(128) DEFAULT '' COMMENT '前端组件路径' AFTER `path`,
  ADD COLUMN `icon` VARCHAR(64) DEFAULT '' COMMENT '菜单图标' AFTER `component`;

-- 5. 补充既有菜单的路由信息（动态路由生成依据）
UPDATE `sys_permission` SET `path`='/system', `component`='Layout', `icon`='system' WHERE `id`=1;
UPDATE `sys_permission` SET `path`='tenant', `component`='system/tenant/index', `icon`='list' WHERE `id`=10;
UPDATE `sys_permission` SET `path`='user', `component`='system/user/index', `icon`='peoples' WHERE `id`=20;
UPDATE `sys_permission` SET `path`='role', `component`='system/role/index', `icon`='peoples' WHERE `id`=30;

-- 6. 新增权限：设备资产（顶级） + 设备管理 + 边缘网关（显式 id，避免自增错位）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(40, 0,  '设备资产', 'device',           'menu',   1, '/device', 'Layout',             'monitor',  NOW(), NOW(), 0),
(41, 40, '设备管理', 'device:menu',       'menu',   1, 'list',    'device/index',       'computer', NOW(), NOW(), 0),
(42, 41, '设备查询', 'device:list',       'button', 1, '',        '',                  '',         NOW(), NOW(), 0),
(43, 41, '设备新增', 'device:add',        'button', 2, '',        '',                  '',         NOW(), NOW(), 0),
(44, 41, '设备修改', 'device:edit',       'button', 3, '',        '',                  '',         NOW(), NOW(), 0),
(45, 41, '设备删除', 'device:remove',     'button', 4, '',        '',                  '',         NOW(), NOW(), 0),
(46, 41, '拓扑维护', 'device:topology',   'button', 5, '',        '',                  '',         NOW(), NOW(), 0),
(50, 40, '边缘网关', 'gateway:menu',      'menu',   2, 'gateway', 'edge/gateway/index','server',   NOW(), NOW(), 0),
(51, 50, '网关查询', 'gateway:list',      'button', 1, '',        '',                  '',         NOW(), NOW(), 0),
(52, 50, '网关新增', 'gateway:add',       'button', 2, '',        '',                  '',         NOW(), NOW(), 0),
(53, 50, '网关修改', 'gateway:edit',      'button', 3, '',        '',                  '',         NOW(), NOW(), 0),
(54, 50, '网关删除', 'gateway:remove',    'button', 4, '',        '',                  '',         NOW(), NOW(), 0),
(55, 50, '重置Token', 'gateway:resetToken','button', 5, '',       '',                  '',         NOW(), NOW(), 0);

-- 7. super_admin 绑定新增权限（40 顶级 + 41/50 菜单 + 42-46/51-55 按钮，共 13 条）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
(1,40),(1,41),(1,42),(1,43),(1,44),(1,45),(1,46),(1,50),(1,51),(1,52),(1,53),(1,54),(1,55);
