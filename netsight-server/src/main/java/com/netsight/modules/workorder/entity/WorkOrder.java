package com.netsight.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 故障工单表（联系单）
 * 告警事件/手动触发自动生成，管理员审核后报修派单，维修闭环
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("work_order")
public class WorkOrder extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 工单编号 WO+时间戳+随机 */
    private String orderNo;

    /** 来源 event告警/manual手动 */
    private String sourceType;

    /** 关联事件ID */
    private Long eventId;

    /** 设备编码 */
    private String deviceCode;

    /** 设备名称 */
    private String deviceName;

    /** 设备IP */
    private String deviceIp;

    /** 设备类型 network/camera/nvr/door_controller */
    private String deviceType;

    /** 部署位置 */
    private String deviceLocation;

    /** 故障类型 offline离线/line_abnormal链路/manual手动 */
    private String faultType;

    /** 级别 critical/warning/info */
    private String severity;

    /** 故障描述 */
    private String description;

    /** 状态 0待处理 1已派单 2维修中 3已完成 4已关闭 5已自动恢复 */
    private Integer status;

    /** 维修人员ID */
    private Long repairerId;

    /** 维修人员姓名（快照） */
    private String repairerName;

    /** 派单时间 */
    private LocalDateTime dispatchTime;

    /** 维修开始时间 */
    private LocalDateTime repairStartTime;

    /** 维修完成时间 */
    private LocalDateTime repairEndTime;

    /** 维修结果 */
    private String repairResult;

    /** 补充说明 */
    private String remark;

    /** 非表字段：处理记录数（列表回显） */
    @TableField(exist = false)
    private Integer recordCount;

    /** 非表字段：关联备件数（列表回显） */
    @TableField(exist = false)
    private Integer partCount;

    /** 非表字段：状态中文（前端展示） */
    @TableField(exist = false)
    private String statusText;
}
