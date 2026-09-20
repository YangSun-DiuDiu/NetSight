package com.netsight.modules.inspection.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 点检任务
 * 由计划按周期生成（也可手动创建），执行人逐项点检
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inspection_task")
public class InspectionTask extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 任务编号（INSP+时间戳） */
    private String taskNo;

    /** 来源计划ID（手动创建为空） */
    private Long planId;

    /** 计划名称（快照） */
    private String planName;

    /** 点检目标ID（设备ID或类型） */
    private Long targetId;

    /** 目标名称（快照） */
    private String targetName;

    /** 目标类型：device / device_type */
    private String targetType;

    /** 目标位置（快照） */
    private String targetLocation;

    /** 执行人用户ID */
    private Long assigneeId;

    /** 执行人姓名（快照） */
    private String assigneeName;

    /** 计划执行日期 */
    private LocalDate planDate;

    /** 状态：0 待执行 / 1 执行中 / 2 已完成 / 3 已逾期 */
    private Integer status;

    /** 异常项数量 */
    private Integer abnormalCount;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 任务备注 */
    private String remark;
}
