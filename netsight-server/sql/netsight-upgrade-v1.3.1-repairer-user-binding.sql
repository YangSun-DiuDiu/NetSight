USE netsight;

-- 维修人员与系统用户绑定；存量数据允许 NULL，新数据由应用层保证非空
ALTER TABLE repairer
    ADD COLUMN user_id BIGINT NULL COMMENT '绑定系统用户ID（sys_user.id）' AFTER repairer_no,
    ADD COLUMN repairer_type TINYINT NOT NULL DEFAULT 0 COMMENT '人员类型 1内聘 2外聘 0存量未分类' AFTER user_id,
    ADD UNIQUE KEY uk_repairer_user (tenant_id, user_id),
    ADD KEY idx_repairer_phone (tenant_id, phone);
