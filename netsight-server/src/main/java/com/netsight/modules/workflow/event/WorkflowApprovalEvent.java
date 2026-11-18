package com.netsight.modules.workflow.event;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流审批结果事件（Spring 事件，解耦业务回调）
 *
 * 审批最终结果（通过/驳回）落库后发布，业务模块（备件出库/入库、点检确认等）
 * 监听该事件做审批存档。中间节点通过不发布结果事件，避免多节点流程提前归档。
 */
@Data
@NoArgsConstructor
public class WorkflowApprovalEvent {

    /** 业务类型：out_stock / in_stock / workorder_accept / inspection_task_confirm 等 */
    private String bizType;

    /** 业务单号（bizId） */
    private String bizId;

    /** 是否通过 */
    private boolean pass;

    /** 审批意见 */
    private String comment;

    /** 最终审批状态：approved / rejected */
    private String status;

    /** 审批单ID（approval_bill.id） */
    private Long billId;

    /** 所属租户ID */
    private Long tenantId;

    /**
     * 兼容历史 5 参调用：审批单ID与租户ID留空，由监听器按业务单号自行定位。
     */
    public WorkflowApprovalEvent(String bizType, String bizId, boolean pass, String comment, String status) {
        this(bizType, bizId, pass, comment, status, null, null);
    }

    public WorkflowApprovalEvent(String bizType, String bizId, boolean pass, String comment,
                                 String status, Long billId, Long tenantId) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.pass = pass;
        this.comment = comment;
        this.status = status;
        this.billId = billId;
        this.tenantId = tenantId;
    }
}
