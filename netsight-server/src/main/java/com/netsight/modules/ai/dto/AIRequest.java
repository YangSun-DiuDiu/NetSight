package com.netsight.modules.ai.dto;

import lombok.Data;

/**
 * AI 模型调用请求（能力层 → 服务层）
 * prompt 体系：system 定角色与输出格式，user 放业务上下文与问题
 */
@Data
public class AIRequest {

    /** 所属租户ID（多租户隔离/审计） */
    private Long tenantId;

    /** 系统提示词（角色/输出格式约束） */
    private String systemPrompt;

    /** 用户提示词（业务上下文 + 问题） */
    private String userPrompt;

    /** 采样温度：运维场景要稳定，默认 0.3 不要发散 */
    private Double temperature = 0.3;

    /** 最大输出 tokens */
    private Integer maxTokens = 800;

    /** 能力标识：knowledge_ask */
    private String feature;
}
