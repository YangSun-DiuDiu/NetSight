-- ============================================================
-- NetSight NAMS V1.1.17 增量 SQL
-- Token 加密列扩容：AES-256-GCM 密文（enc:前缀+iv+tag ≈ 85字符）超出 varchar(64)
-- 先扩列，再依赖 TokenCryptoMigration 启动迁移把存量明文转为密文
-- ============================================================

ALTER TABLE `edge_gateway`
    MODIFY COLUMN `gateway_token` VARCHAR(128) NOT NULL COMMENT '接入Token（网关上报鉴权，AES-256-GCM密文）',
    MODIFY COLUMN `pushplus_token` VARCHAR(128) DEFAULT NULL COMMENT 'PushPlus推送Token（AES-256-GCM密文）';

ALTER TABLE `sys_tenant`
    MODIFY COLUMN `webhook_token` VARCHAR(128) DEFAULT NULL COMMENT '租户Webhook接入Token（AlertManager告警上报鉴权，AES-256-GCM密文）',
    MODIFY COLUMN `pushplus_token` VARCHAR(128) DEFAULT NULL COMMENT 'PushPlus推送Token（AES-256-GCM密文）';

-- 校验：四列长度应为 128
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'netsight'
  AND ((TABLE_NAME = 'edge_gateway' AND COLUMN_NAME IN ('gateway_token', 'pushplus_token'))
    OR (TABLE_NAME = 'sys_tenant' AND COLUMN_NAME IN ('webhook_token', 'pushplus_token')));
