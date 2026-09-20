-- =============================================================
-- NetSight V1.1.19：通知消息模板绑定具体通道实例（方案B）
-- 给 notification_template 增加 channel_id 字段，直接绑定 notify_channel 实例
-- 老数据 channel_id 为 NULL，发送时按 channelType 走类型默认模板兜底，零迁移
-- =============================================================

ALTER TABLE `notification_template`
  ADD COLUMN `channel_id` BIGINT NULL DEFAULT NULL COMMENT '绑定的具体通道实例ID(notify_channel.id)；NULL=该类型默认模板兜底' AFTER `channel_type`;

-- 校验
SHOW COLUMNS FROM `notification_template` LIKE 'channel_id';
