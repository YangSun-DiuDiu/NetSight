package com.netsight.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * AI 调用用量审计表
 * 记录每次模型调用的租户/能力/用量/费用/状态，用于成本审计与故障排查
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_usage_log")
public class AIUsageLog extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 能力标识：knowledge_ask（阶段1仅知识库问诊） */
    private String feature;

    /** 实际模型名 */
    private String model;

    /** 输入 tokens */
    private Integer promptTokens;

    /** 输出 tokens */
    private Integer completionTokens;

    /** 估算费用（元） */
    private BigDecimal costYuan;

    /** 调用状态 success/timeout/failed */
    private String status;

    /** 失败原因（截断200字符） */
    private String errorMsg;
}
