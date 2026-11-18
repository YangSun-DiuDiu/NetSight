-- 点检任务确认审批迁移（2026-11-18）
-- 适用：MySQL 8.0，库 netsight
-- 说明：本脚本为一次性迁移，执行前请确认 inspection_task 尚未存在以下三列。

ALTER TABLE inspection_task
    ADD COLUMN approval_status VARCHAR(20) NULL COMMENT '审批状态：processing 审批中 / approved 已通过 / rejected 已驳回',
    ADD COLUMN approval_bill_id BIGINT NULL COMMENT '关联审批单ID（approval_bill.id）',
    ADD COLUMN attempt_no INT NOT NULL DEFAULT 1 COMMENT '当前执行轮次（首次1，驳回后+1）';

-- 为按任务号查询审批明细加索引（若已有 task_no 唯一索引可跳过）
CREATE INDEX idx_inspection_task_tenant_taskno ON inspection_task (tenant_id, task_no);

-- ============================================================
-- tenant 17：点检任务确认流程（1 个审批节点：租户管理员）
-- 流程名称固定“点检任务确认”，流程编码按租户隔离
-- ============================================================
INSERT INTO flow_definition
    (id, flow_code, flow_name, model_value, category, form_custom, form_path,
     version, is_publish, activity_status, listener_type, listener_path, ext,
     del_flag, tenant_id, create_time, create_by)
SELECT 2122000000000000001, 'inspection_task_confirm_t17', '点检任务确认', 'CLASSICS', 'inspection',
       'N', NULL, 1, 1, 1, NULL, NULL, NULL, 0, 17, NOW(), 'system'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM flow_definition WHERE flow_code = 'inspection_task_confirm_t17' AND tenant_id = 17
);

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
     coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
     version, create_time, create_by, update_time, update_by, ext, del_flag, tenant_id)
SELECT 2122000000000000011, 0, 2122000000000000001, 'start', '开始', NULL, NULL,
       NULL, NULL, NULL, NULL, 'N', NULL, 1, NOW(), 'system', NOW(), NULL, NULL, 0, 17
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 2122000000000000011);

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
     coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
     version, create_time, create_by, update_time, update_by, ext, del_flag, tenant_id)
SELECT 2122000000000000012, 1, 2122000000000000001, 'approve1', '点检确认（租户管理员）',
       'tenant_admin', NULL, NULL, NULL, NULL, NULL, 'N', NULL, 1, NOW(), 'system', NOW(), NULL, NULL, 0, 17
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 2122000000000000012);

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
     coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
     version, create_time, create_by, update_time, update_by, ext, del_flag, tenant_id)
SELECT 2122000000000000013, 2, 2122000000000000001, 'end', '结束', NULL, NULL,
       NULL, NULL, NULL, NULL, 'N', NULL, 1, NOW(), 'system', NOW(), NULL, NULL, 0, 17
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 2122000000000000013);

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
     skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id)
SELECT 2122000000000000021, 2122000000000000001, 'start', 0, 'approve1', 1,
       '提交', 'PASS', NULL, NULL, 0, 17
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 2122000000000000021);

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
     skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id)
SELECT 2122000000000000022, 2122000000000000001, 'approve1', 1, 'end', 2,
       '确认通过', 'PASS', NULL, NULL, 0, 17
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 2122000000000000022);
