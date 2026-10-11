-- =====================================================
-- NetSight AI 模块建表（阶段 1：知识库问诊）
-- 库：netsight  执行：mysql -usadmin -p < ai_usage_log.sql
-- 2026-10-11
-- =====================================================

-- AI 用量审计表（记录每次模型调用：租户/能力/用量/费用/状态）
CREATE TABLE IF NOT EXISTS ai_usage_log (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id         BIGINT       NOT NULL COMMENT '租户ID',
  feature           VARCHAR(32)  NOT NULL COMMENT '能力标识：knowledge_ask',
  model             VARCHAR(64)  NULL COMMENT '模型名',
  prompt_tokens     INT          NULL COMMENT '输入tokens',
  completion_tokens INT          NULL COMMENT '输出tokens',
  cost_yuan         DECIMAL(10,6) NULL COMMENT '估算费用（元）',
  status            VARCHAR(16)  NOT NULL COMMENT 'success/failed',
  error_msg         VARCHAR(200) NULL COMMENT '失败原因（截断200）',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time       DATETIME     NULL COMMENT '修改时间',
  del_flag          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0正常/2已删除',
  KEY idx_ai_log_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用用量审计';
