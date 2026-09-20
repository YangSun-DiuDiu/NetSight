package com.netsight.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工单处理记录（生成/派单/接单/完成/关闭时间线）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order_record")
public class WorkOrderRecord extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 工单ID */
    private Long orderId;

    /** 动作 auto_create/dispatch/repair_start/complete/close/recover */
    private String action;

    /** 操作人 */
    private String operator;

    /** 处理内容 */
    private String content;
}
