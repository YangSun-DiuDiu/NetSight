package com.netsight.modules.spare.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 备件出入库记录（入库/领用出库/返修入库/报废，关联工单）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("spare_part_record")
public class SparePartRecord extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 备件ID */
    private Long partId;

    /** 备件编号（快照） */
    private String partNo;

    /** 备件名称（快照） */
    private String partName;

    /** 类型 in入库/out领用出库/return_in返修入库/repair返修/scrap报废/adjust调整 */
    private String recordType;

    /** 数量（out 为负数扣减） */
    private Integer quantity;

    /** 关联工单ID（领用出库必填） */
    private Long orderId;

    /** 工单编号（快照） */
    private String orderNo;

    /** 操作人 */
    private String operator;

    /** 备注 */
    private String remark;
}
