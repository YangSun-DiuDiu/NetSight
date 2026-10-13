package com.netsight.modules.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 统一审批单
 *
 * 对接 Warm-Flow 引擎实例（flow_instance）的业务载体：
 * 记录"哪张业务单据、走了哪个流程、当前到哪个节点、处于什么状态"，
 * 供流程定义、待办、审批动作使用，避免各业务模块各自维护审批状态。
 *
 * 租户隔离：tenant_id 走 MyBatis-Plus 行级拦截器自动填充/过滤（与业务表一致，bigint）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("approval_bill")
public class ApprovalBill extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 业务类型：out_stock 备件出库 / in_stock 备件入库 / workorder_accept 工单验收 / 领料申请等 */
    private String bizType;

    /** 业务单号（业务侧唯一标识，如出库单号/工单号） */
    private String bizId;

    /** 流程编码（Warm-Flow 定义编码，如 spare_out_stock） */
    private String flowCode;

    /** 流程名称（冗余展示用） */
    private String flowName;

    /** 引擎流程实例ID（flow_instance.id） */
    private Long processInstanceId;

    /** 审批状态：processing 审批中 / approved 已通过 / rejected 已驳回 / canceled 已撤销 */
    private String approvalStatus;

    /** 发起人ID */
    private Long applyUserId;

    /** 发起人姓名（冗余展示用） */
    private String applyUserName;

    /** 当前节点名称（引擎实例 nodeName 冗余） */
    private String currentNode;

    /** 审批标题（如：备件出库申请 #OUT20261012-001） */
    private String title;

    /** 审批意见/备注 */
    private String remark;
}
