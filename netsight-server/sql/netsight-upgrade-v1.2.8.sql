-- ============================================================
-- NetSight V1.2.8 通知渠道实例管理（notify_channel）
-- 升级内容：
--   1. 新建 notify_channel 表（渠道实例化，参数 DB 化，多租户隔离）
--   2. 新增菜单/按钮权限 140-146
--   3. 存量迁移：每个租户自动建 1 条 pushplus 实例（token 从 sys_tenant 搬）
--   4. 旧规则 channels_json 中 "pushplus" 替换为该实例 ID
-- ============================================================

CREATE TABLE IF NOT EXISTS `notify_channel` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
  `channel_type` VARCHAR(32) NOT NULL COMMENT '渠道类型：aliyun_sms/tencent_sms/dingtalk/wechat_work/wechat_app/feishu/serverchan/pushplus/email/webhook',
  `channel_name` VARCHAR(64) NOT NULL COMMENT '实例名称',
  `config_json` TEXT COMMENT '渠道参数 JSON（密钥类已 AES 加密）',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '启用：1启用/0停用',
  `health_status` VARCHAR(16) NOT NULL DEFAULT 'unknown' COMMENT '健康状态：unknown/healthy/down',
  `last_check_time` DATETIME NULL COMMENT '最近健康检查时间',
  `remark` VARCHAR(255) NULL COMMENT '备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `del_flag` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_type` (`tenant_id`, `channel_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知渠道实例';

-- ========== 权限 ==========
-- 140 渠道管理顶级（挂在告警中心 60 下？告警中心顶级 parent_id=60 已知）
-- 告警中心顶级 perm_key=alert:menu, id=60
INSERT IGNORE INTO `sys_permission`
  (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `path`, `component`, `icon`, `sort`, `create_time`, `update_time`)
VALUES
  (140, 60, '渠道管理', 'alert:channel:menu', 'menu', 'channel', 'alert/channel/index', 'el-icon-message', 6, NOW(), NOW());

-- 按钮权限
INSERT IGNORE INTO `sys_permission`
  (`id`, `parent_id`, `perm_name`, `perm_key`, `perm_type`, `sort`, `create_time`, `update_time`)
VALUES
  (141, 140, '渠道查询', 'alert:channel:list',   'button', 1, NOW(), NOW()),
  (142, 140, '渠道新增', 'alert:channel:add',    'button', 2, NOW(), NOW()),
  (143, 140, '渠道修改', 'alert:channel:edit',   'button', 3, NOW(), NOW()),
  (144, 140, '渠道删除', 'alert:channel:remove', 'button', 4, NOW(), NOW()),
  (145, 140, '渠道测试', 'alert:channel:test',   'button', 5, NOW(), NOW()),
  (146, 140, '渠道健康检查', 'alert:channel:check', 'button', 6, NOW(), NOW());

-- 角色绑定：role1 super_admin / role2 tenant_admin 全绑；role3 ops 绑 140+141+145；role4 repairer 不绑
INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, id FROM `sys_permission` WHERE id BETWEEN 140 AND 146
UNION ALL SELECT 2, id FROM `sys_permission` WHERE id BETWEEN 140 AND 146
UNION ALL SELECT 3, id FROM `sys_permission` WHERE id IN (140, 141, 145);

-- ============================================================
-- 存量迁移：为每个已配置 pushplus_token 的租户建 1 条 pushplus 实例
-- ============================================================
INSERT INTO `notify_channel`
  (`tenant_id`, `channel_type`, `channel_name`, `config_json`, `enabled`, `health_status`, `remark`, `create_time`, `update_time`)
SELECT
  t.id,
  'pushplus',
  CONCAT(t.tenant_name, '-PushPlus推送'),
  CONCAT('{"token":"', IFNULL(t.pushplus_token, ''), '"}'),
  1,
  'unknown',
  '系统自动迁移（来源 sys_tenant.pushplus_token）',
  NOW(), NOW()
FROM `sys_tenant` t
WHERE t.del_flag = 0
  AND t.status = 1
  AND t.pushplus_token IS NOT NULL
  AND t.pushplus_token != ''
  AND NOT EXISTS (
    SELECT 1 FROM `notify_channel` c
    WHERE c.tenant_id = t.id AND c.channel_type = 'pushplus' AND c.del_flag = 0
  );

-- ============================================================
-- 旧规则 channels_json 中 "pushplus" 替换为该租户 pushplus 实例 ID
-- ============================================================
UPDATE `notification_rule` r
JOIN `notify_channel` c
  ON c.tenant_id = r.tenant_id AND c.channel_type = 'pushplus' AND c.del_flag = 0
SET r.channels_json = REPLACE(r.channels_json, '"pushplus"', CONCAT('"', c.id, '"'))
WHERE r.del_flag = 0
  AND r.channels_json LIKE '%"pushplus"%';

-- ========== 校验查询 ==========
SELECT 'notify_channel' AS tbl, COUNT(*) AS cnt FROM `notify_channel`;
SELECT id, tenant_id, channel_type, channel_name, enabled FROM `notify_channel` ORDER BY id;
SELECT id, rule_name, channels_json FROM `notification_rule` WHERE del_flag = 0;
