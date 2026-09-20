-- ============================================================
-- NetSight V1.1.6 升级脚本（租户级 Webhook Token）
-- 方案依据：V1.1 方案 5.5 第 7 小节「Webhook 接入鉴权与租户级 Token 管理」
-- 1. sys_tenant 新增 webhook_token / webhook_token_time 字段
-- 2. 存量租户初始化生成 Token（UUID 去横线 32 位，与网关 Token 一致）
-- 3. webhook_token 唯一索引（防止重复）
-- 幂等说明：ALTER 加列/索引不可重复执行（重复会报 Duplicate column/key），
--           UPDATE 初始化可重复执行（WHERE 条件已覆盖已生成租户）
-- ============================================================

-- ---------- 1. sys_tenant 加列 ----------
ALTER TABLE `sys_tenant`
    ADD COLUMN `webhook_token` VARCHAR(64) DEFAULT NULL COMMENT '租户Webhook接入Token（AlertManager告警上报鉴权，Token即租户身份）' AFTER `expire_time`,
    ADD COLUMN `webhook_token_time` DATETIME DEFAULT NULL COMMENT 'Webhook Token 生成/重置时间' AFTER `webhook_token`;

-- ---------- 2. 存量租户初始化 Token ----------
UPDATE `sys_tenant`
SET `webhook_token`      = REPLACE(UUID(), '-', ''),
    `webhook_token_time` = NOW()
WHERE `del_flag` = 0
  AND (`webhook_token` IS NULL OR `webhook_token` = '');

-- ---------- 3. 唯一索引 ----------
ALTER TABLE `sys_tenant`
    ADD UNIQUE INDEX `uk_webhook_token` (`webhook_token`);
