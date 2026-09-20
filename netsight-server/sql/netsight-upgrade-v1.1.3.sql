-- =====================================================================
-- NetSight（NAMS）V1.1.3 增量升级脚本（第 4 周：监控告警链路）
-- 事件中心 + 通知通道中心 + AlertManager Webhook 对接
-- 适用：192.168.1.55 MySQL（库 netsight）
-- 注意：权限/菜单 INSERT 一律显式指定 id，避免 auto_increment 错位
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 事件记录表（事件中心：统一接收、存储、路由分发）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID（webhook 由网关所属租户决定）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型编码（device_offline/device_line_abnormal/device_recovered/...）',
    event_source VARCHAR(32) NOT NULL DEFAULT 'alertmanager' COMMENT '事件来源：alertmanager/manual/system',
    severity VARCHAR(16) NOT NULL DEFAULT 'warning' COMMENT '级别：critical/warning/resolved/info',
    biz_id VARCHAR(128) DEFAULT NULL COMMENT '业务ID（告警指纹/工单号，用于去重）',
    device_name VARCHAR(128) DEFAULT NULL COMMENT '设备名称',
    device_ip VARCHAR(64) DEFAULT NULL COMMENT '设备IP',
    device_type VARCHAR(32) DEFAULT NULL COMMENT '设备类型（network/camera/...）',
    location VARCHAR(255) DEFAULT NULL COMMENT '部署位置',
    labels_json TEXT COMMENT '原始标签 JSON',
    content_vars_json TEXT COMMENT '模板变量 JSON',
    content TEXT COMMENT '渲染后的通知内容',
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending/processing/sent/part_failed/failed',
    rule_id BIGINT DEFAULT NULL COMMENT '匹配的路由规则ID',
    rule_name VARCHAR(128) DEFAULT NULL COMMENT '匹配的路由规则名称',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '0正常/2已删',
    INDEX idx_event_type (event_type),
    INDEX idx_biz (biz_id),
    INDEX idx_tenant_status (tenant_id, status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件记录（事件中心）';

-- ---------------------------------------------------------------------
-- 2. 通知路由规则表（管理员配置：事件类型 → 通道/接收人/模板）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    event_type VARCHAR(64) NOT NULL COMMENT '关联事件类型',
    condition_json TEXT COMMENT '触发条件表达式（JSON：如 {"severity":"critical"}，空=全部匹配）',
    receiver_strategy_json TEXT COMMENT '接收人策略（JSON：如 {"type":"fixed","receivers":["138xxxx"]}）',
    channels_json TEXT COMMENT '通道列表（JSON 数组：["sms","wechat"]）',
    template_id BIGINT DEFAULT NULL COMMENT '关联消息模板ID',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用/0停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    INDEX idx_tenant (tenant_id),
    INDEX idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知路由规则（自动发送策略）';

-- ---------------------------------------------------------------------
-- 3. 消息模板表（各通道内容模板，{{var}} 占位符）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    template_code VARCHAR(64) NOT NULL COMMENT '模板编码（唯一）',
    template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
    channel_type VARCHAR(32) NOT NULL DEFAULT 'wechat' COMMENT '适用通道：sms/wechat',
    content TEXT NOT NULL COMMENT '模板内容（支持 {{var}} 占位符）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_template_code_channel (tenant_id, template_code, channel_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知消息模板';

-- ---------------------------------------------------------------------
-- 4. 通知发送日志表（每次通道发送记录，支持审计与失败告警）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT '租户ID',
    event_id BIGINT DEFAULT NULL COMMENT '关联事件ID',
    rule_id BIGINT DEFAULT NULL COMMENT '关联规则ID',
    rule_name VARCHAR(128) DEFAULT NULL COMMENT '规则名称（快照）',
    event_type VARCHAR(64) DEFAULT NULL COMMENT '事件类型（快照）',
    channel_type VARCHAR(32) NOT NULL COMMENT '发送通道：sms/wechat',
    receivers_json TEXT COMMENT '接收人列表 JSON',
    content TEXT COMMENT '实际发送内容',
    biz_id VARCHAR(128) DEFAULT NULL COMMENT '业务ID（去重/追溯）',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否成功：1成功/0失败',
    error_msg VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    third_party_msg_id VARCHAR(128) DEFAULT NULL COMMENT '第三方通道返回的消息ID',
    cost_time BIGINT DEFAULT NULL COMMENT '发送耗时 ms',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag TINYINT NOT NULL DEFAULT 0,
    INDEX idx_event (event_id),
    INDEX idx_tenant_channel (tenant_id, channel_type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知发送日志';

-- ---------------------------------------------------------------------
-- 1b. 兼容旧版唯一键（首版 v1.1.3 曾用 tenant+template_code 唯一，无法同 code 多通道）
--     改为 租户+编码+通道 联合唯一（已存在 uk_template_code 的环境执行一次）
-- ---------------------------------------------------------------------
-- ALTER TABLE notification_template DROP INDEX uk_template_code;
-- ALTER TABLE notification_template ADD UNIQUE KEY uk_template_code_channel (tenant_id, template_code, channel_type);

-- ---------------------------------------------------------------------
-- 5. sys_permission 补默认图标（可选，不影响既有数据）
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- 6. 初始化消息模板（租户1，内置事件类型默认模板）
-- ---------------------------------------------------------------------
INSERT INTO notification_template (id, tenant_id, template_code, template_name, channel_type, content, enabled, del_flag) VALUES
(1, 1, 'tpl_device_offline', '设备离线告警', 'sms', '【运维告警】高危故障：{{device_name}}（{{device_ip}}），【{{device_type}}】于{{time}}离线，位置：{{location}}，请尽快排查！', 1, 0),
(2, 1, 'tpl_device_offline', '设备离线告警', 'wechat', '⚠️ 设备离线告警\n设备：{{device_name}}（{{device_ip}}）\n类型：{{device_type}}\n位置：{{location}}\n级别：{{severity}}\n时间：{{time}}\n请立即排查处理！', 1, 0),
(3, 1, 'tpl_device_line', '设备外线异常', 'wechat', '⚠️ 设备外线异常\n设备：{{device_name}}（{{device_ip}}）\n类型：{{device_type}}\n位置：{{location}}\n级别：{{severity}}\n时间：{{time}}\n设备在线，建议巡检线路！', 1, 0),
(4, 1, 'tpl_device_recovered', '故障恢复通知', 'wechat', '✅ 故障已恢复\n设备：{{device_name}}（{{device_ip}}）\n位置：{{location}}\n恢复时间：{{time}}', 1, 0),
(5, 1, 'tpl_manual', '手动通知', 'sms', '【NetSight】{{content}}', 1, 0),
(6, 1, 'tpl_manual', '手动通知', 'wechat', '📢 NetSight 通知\n{{content}}', 1, 0);

-- ---------------------------------------------------------------------
-- 7. 初始化路由规则（租户1，内置事件类型默认规则）
-- ---------------------------------------------------------------------
INSERT INTO notification_rule (id, tenant_id, rule_name, event_type, condition_json, receiver_strategy_json, channels_json, template_id, enabled, del_flag) VALUES
(1, 1, '设备离线高危告警', 'device_offline', '{"severity":"critical"}', '{"type":"fixed","receivers":["13800000000"]}', '["sms","wechat"]', 1, 1, 0),
(2, 1, '设备离线普通告警', 'device_offline', '{"severity":"warning"}', '{"type":"fixed","receivers":["13800000000"]}', '["wechat"]', 2, 1, 0),
(3, 1, '外线异常告警', 'device_line_abnormal', '{}', '{"type":"fixed","receivers":["13800000000"]}', '["wechat"]', 3, 1, 0),
(4, 1, '故障恢复通知', 'device_recovered', '{}', '{"type":"fixed","receivers":["13800000000"]}', '["wechat"]', 4, 1, 0);

-- ---------------------------------------------------------------------
-- 8. 菜单/权限（显式 id，避免 auto_increment 错位）
--    60 告警中心顶级；61-64 子菜单；65-75 按钮
-- ---------------------------------------------------------------------
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `path`, `component`, `icon`, `create_time`, `update_time`, `del_flag`) VALUES
(60, 0,  '告警中心', 'alert:menu',      'menu',   1, '/alert', 'Layout',             'bell',         NOW(), NOW(), 0),
(61, 60, '事件记录', 'alert:event:menu', 'menu',  1, 'event',   'alert/event/index', 'list',         NOW(), NOW(), 0),
(62, 60, '通知规则', 'alert:rule:menu',  'menu',  2, 'rule',    'alert/rule/index',  'edit',         NOW(), NOW(), 0),
(63, 60, '消息模板', 'alert:template:menu','menu',3, 'template','alert/template/index','documentation', NOW(), NOW(), 0),
(64, 60, '发送日志', 'alert:log:menu',   'menu',   4, 'log',     'alert/log/index',   'log',          NOW(), NOW(), 0),
(65, 61, '事件查询', 'alert:event:list', 'button', 1, '',        '',                  '',             NOW(), NOW(), 0),
(66, 61, '手动发送', 'alert:event:manual','button',2, '',        '',                  '',             NOW(), NOW(), 0),
(67, 62, '规则查询', 'alert:rule:list',  'button', 1, '',        '',                  '',             NOW(), NOW(), 0),
(68, 62, '规则新增', 'alert:rule:add',   'button', 2, '',        '',                  '',             NOW(), NOW(), 0),
(69, 62, '规则修改', 'alert:rule:edit',  'button', 3, '',        '',                  '',             NOW(), NOW(), 0),
(70, 62, '规则删除', 'alert:rule:remove', 'button', 4, '',       '',                  '',             NOW(), NOW(), 0),
(71, 63, '模板查询', 'alert:template:list','button',1, '',       '',                  '',             NOW(), NOW(), 0),
(72, 63, '模板新增', 'alert:template:add', 'button',2, '',       '',                  '',             NOW(), NOW(), 0),
(73, 63, '模板修改', 'alert:template:edit','button',3, '',       '',                  '',             NOW(), NOW(), 0),
(74, 63, '模板删除', 'alert:template:remove','button',4,'',      '',                  '',             NOW(), NOW(), 0),
(75, 64, '日志查询', 'alert:log:list',   'button', 1, '',        '',                  '',             NOW(), NOW(), 0);

-- ---------------------------------------------------------------------
-- 9. super_admin（role_id=1）绑定新增 16 条权限（60-75）
--    先删除可能存在的旧绑定，再显式插入，保证幂等
-- ---------------------------------------------------------------------
DELETE FROM sys_role_permission WHERE role_id = 1 AND permission_id >= 60 AND permission_id <= 75;
INSERT INTO sys_role_permission (role_id, permission_id) VALUES
(1, 60), (1, 61), (1, 62), (1, 63), (1, 64),
(1, 65), (1, 66), (1, 67), (1, 68), (1, 69), (1, 70),
(1, 71), (1, 72), (1, 73), (1, 74), (1, 75);
