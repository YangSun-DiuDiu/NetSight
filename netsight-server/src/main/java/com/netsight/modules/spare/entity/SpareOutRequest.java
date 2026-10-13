package com.netsight.modules.spare.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 备件出库申请单（方案B：提交即扣库存 + 审批存档）
 *
 * 领料/出库发起时：先扣库存（spare_part + spare_part_record + work_order_part 绑定），
 * 同时登记本单并启动 warm-flow 审批（bizType=out_stock / in_stock），
 * 审批结果仅存档（approved/rejected 同步本表），驳回后可按同 bizId 重新提交。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("spare_out_request")
public class SpareOutRequest extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 关联工单ID（领料强制关联） */
    private Long orderId;

    /** 工单编号（快照） */
    private String orderNo;

    /** 出库单号（业务唯一，如 OUT-xxx / IN-xxx） */
    private String bizId;

    /** 备件明细 JSON：[{"partId":1,"quantity":2,"partName":"类型 品牌 型号","unit":"个"}] */
    private String items;

    /** 审批状态 processing审批中 / approved已通过 / rejected已驳回 / canceled已撤销 */
    private String approvalStatus;

    /** 关联审批单ID（approval_bill.id） */
    private Long billId;

    /** 流程实例ID（flow_instance.id） */
    private Long processInstanceId;

    /** 发起人ID */
    private Long applyUserId;

    /** 发起人姓名 */
    private String applyUserName;

    /** 备注 / 驳回意见 */
    private String remark;
}
