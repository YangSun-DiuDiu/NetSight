-- AI 诊断 P0：可重复执行的数据部分（权限 + 测试租户知识库种子）
-- 结构变更见 ai_p0_migration.sql 的 ALTER 段；本文件可在迁移后反复执行。

INSERT IGNORE INTO sys_permission
    (parent_id, perm_name, perm_key, perm_type, sort, path, component, icon)
VALUES
    (124, 'AI 诊断', 'ai:diagnose', 'api', 9, '', '', '');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission WHERE perm_key = 'ai:diagnose';

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
