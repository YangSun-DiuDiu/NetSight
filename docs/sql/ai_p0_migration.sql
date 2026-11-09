-- =====================================================================
-- NetSight AI 诊断 P0 生产化迁移（测试环境 115 / 生产环境各执行一次）
--
-- 内容：
--   1) ai_usage_log 补齐审计字段：user_id / order_id / request_id / latency_ms / prompt_version
--   2) 新增维修员专用权限 ai:diagnose，并授权给 repairer 角色（role_id=4）
--   3) 为租户 7 复制租户 1 的启用知识库条目（测试环境联调用；生产按实际租户修改 @seed_tenant）
--
-- 注意：
--   - ALTER TABLE 为一次性迁移，请勿重复执行；重复执行会报 Duplicate column。
--   - 权限插入使用 INSERT IGNORE，可重复执行。
--   - 知识库复制使用 NOT EXISTS(title) 去重，可重复执行。
--   - 生产执行前先备份：mysqldump netsight ai_usage_log fault_article sys_permission sys_role_permission
-- =====================================================================

-- 1. 审计字段
ALTER TABLE ai_usage_log
    ADD COLUMN user_id BIGINT NULL COMMENT '发起用户ID' AFTER tenant_id,
    ADD COLUMN order_id BIGINT NULL COMMENT '关联工单ID' AFTER user_id,
    ADD COLUMN request_id VARCHAR(64) NULL COMMENT '请求链路ID' AFTER order_id,
    ADD COLUMN latency_ms INT NULL COMMENT '本次调用耗时(ms)' AFTER cost_yuan,
    ADD COLUMN prompt_version VARCHAR(32) NULL COMMENT '提示词版本' AFTER latency_ms;

ALTER TABLE ai_usage_log
    ADD KEY idx_ai_log_user_time (tenant_id, user_id, create_time);

-- 2. 维修员专用权限 ai:diagnose
--    perm_type=api，不生成 PC 菜单；只用于 @PreAuthorize 判定。
INSERT IGNORE INTO sys_permission
    (parent_id, perm_name, perm_key, perm_type, sort, path, component, icon)
VALUES
    (124, 'AI 诊断', 'ai:diagnose', 'api', 9, '', '', '');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE perm_key = 'ai:diagnose';

-- 3. 测试环境：给租户 7 复制租户 1 的启用知识库条目（18 条）
--    生产环境请把 @seed_tenant 改成实际租户 ID，或改为由知识库运营导入。
SET @seed_tenant = 7;

INSERT INTO fault_article
    (tenant_id, title, category, device_type, brand, model, symptom, cause, solution,
     keywords, severity_ref, view_count, status, create_by, create_time, update_time, del_flag)
SELECT
    @seed_tenant, src.title, src.category, src.device_type, src.brand, src.model,
    src.symptom, src.cause, src.solution, src.keywords, src.severity_ref,
    0, 1, 'ai-p0-seed', NOW(), NOW(), 0
FROM fault_article src
WHERE src.tenant_id = 1
  AND src.status = 1
  AND src.del_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM fault_article dst
      WHERE dst.tenant_id = @seed_tenant
        AND dst.title = src.title
        AND dst.del_flag = 0
  );
