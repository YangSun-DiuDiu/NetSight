-- ============================================================
-- NetSight v1.3.1 升级脚本：维修人员与系统用户绑定
-- 注意：本脚本仅在目标库执行一次，不可重复执行。
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS / ADD KEY IF NOT EXISTS，
-- 重复执行会报 Duplicate column name 'user_id' / Duplicate column name 'repairer_type' /
-- Duplicate key name 'uk_repairer_user' / Duplicate key name 'idx_repairer_phone'。
-- 重跑前请先确认 repairer 表是否已有 user_id、repairer_type 列，
-- 以及 uk_repairer_user(tenant_id,user_id)、idx_repairer_phone(tenant_id,phone) 索引；
-- 若已存在对应列或索引，请手工移除相关 ALTER 片段后再执行。
-- ============================================================
USE netsight;

-- 维修人员与系统用户绑定；存量数据允许 NULL，新数据由应用层保证非空
ALTER TABLE repairer
    ADD COLUMN user_id BIGINT NULL COMMENT '绑定系统用户ID（sys_user.id）' AFTER repairer_no,
    ADD COLUMN repairer_type TINYINT NOT NULL DEFAULT 0 COMMENT '人员类型 1内聘 2外聘 0存量未分类' AFTER user_id,
    ADD UNIQUE KEY uk_repairer_user (tenant_id, user_id),
    ADD KEY idx_repairer_phone (tenant_id, phone);
