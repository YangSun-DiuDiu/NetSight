package com.netsight.modules.workflow.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流审批结果事件（Spring 事件，解耦业务回调）
 *
 * 审批（通过/驳回）落库后发布，业务模块（备件出库/入库等）监听该事件做审批存档。
 * 方案B语义：业务动作（扣/加库存）在发起时已执行，审批结果仅存档同步业务单状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowApprovalEvent {

    /** 业务类型：out_stock / in_stock / workorder_accept 等 */
    private String bizType;

    /** 业务单号（bizId） */
    private String bizId;

    /** 是否通过 */
    private boolean pass;

    /** 审批意见 */
    private String comment;

    /** 最终审批状态：approved / rejected */
    private String status;
}
