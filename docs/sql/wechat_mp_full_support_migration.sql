-- 服务号消息推送全面支持：结构变更（幂等）
-- 适用库：netsight（115 测试环境）；生产 1.94.26.126 不执行本脚本。

-- notification_log 三列
SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'channel_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN channel_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'template_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN template_id VARCHAR(128) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'recipient_results_json');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN recipient_results_json TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- notify_contact 两列
SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notify_contact' AND column_name = 'wechat_app_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notify_contact ADD COLUMN wechat_app_id VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notify_contact' AND column_name = 'wechat_bound_at');
SET @sql := IF(@col = 0, 'ALTER TABLE notify_contact ADD COLUMN wechat_bound_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
