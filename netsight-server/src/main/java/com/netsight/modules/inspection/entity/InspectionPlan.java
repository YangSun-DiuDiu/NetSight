package com.netsight.modules.inspection.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 点检计划
 * 按周期（daily/weekly/monthly）为目标生成点检任务
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inspection_plan")
public class InspectionPlan extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 计划名称 */
    private String planName;

    /** 周期：daily 每日 / weekly 每周一 / monthly 每月1日 */
    private String cycleType;

    /** 对象：device 指定设备 / device_type 按类型 */
    private String targetType;

    /** 目标ID列表（JSON数组：设备ID 或 设备类型） */
    private String targetIds;

    /** 默认执行人用户ID */
    private Long assigneeId;

    /** 执行人姓名（快照） */
    private String assigneeName;

    /** 生效开始日期 */
    private LocalDate startDate;

    /** 生效结束日期（空=长期） */
    private LocalDate endDate;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
