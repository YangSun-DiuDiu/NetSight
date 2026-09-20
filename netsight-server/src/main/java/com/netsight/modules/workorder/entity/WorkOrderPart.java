package com.netsight.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工单备件关联（每张工单可关联多个备件领用）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order_part")
public class WorkOrderPart extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 工单ID */
    private Long orderId;

    /** 备件ID */
    private Long partId;

    /** 备件编号（快照） */
    private String partNo;

    /** 备件名称（类型+品牌+型号） */
    private String partName;

    /** 领用数量 */
    private Integer quantity;

    /** 单位 */
    private String unit;
}
