package com.netsight.modules.inspection.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 点检记录
 * 任务下逐项检查结果；异常项可联动报修工单
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inspection_record")
public class InspectionRecord extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 点检任务ID */
    private Long taskId;

    /** 点检项ID（快照源） */
    private Long itemId;

    /** 点检项名称（快照） */
    private String itemName;

    /** 检查内容（快照） */
    private String checkContent;

    /** 检查标准（快照） */
    private String checkStandard;

    /** 结果类型 */
    private String resultType;

    /** 结果：0 正常 / 1 异常 */
    private Integer result;

    /** 文本结果（text 型填写） */
    private String textResult;

    /** 备注 */
    private String remark;

    /** 异常报修关联工单ID */
    private Long orderId;

    /** 操作人用户ID */
    private Long operatorId;

    /** 操作人姓名（快照） */
    private String operatorName;
}
