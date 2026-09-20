package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 告警事件记录（事件中心核心表）
 * 统一接收 AlertManager webhook / 手动触发事件，按规则路由分发
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("event_record")
public class EventRecord extends BaseEntity {

    /** 所属租户ID（webhook 由网关所属租户决定，手动触发为当前租户） */
    private Long tenantId;

    /** 事件类型编码：device_offline / device_line_abnormal / device_recovered / ... */
    private String eventType;

    /** 事件来源：alertmanager / manual / system */
    private String eventSource;

    /** 级别：critical / warning / resolved / info */
    private String severity;

    /** 业务ID（告警指纹/工单号，用于去重） */
    private String bizId;

    /** 设备名称 */
    private String deviceName;

    /** 设备IP */
    private String deviceIp;

    /** 设备类型：network/camera/nvr/door_controller */
    private String deviceType;

    /** 部署位置 */
    private String location;

    /** 原始标签 JSON */
    private String labelsJson;

    /** 模板变量 JSON */
    private String contentVarsJson;

    /** 渲染后的通知内容 */
    private String content;

    /** 处理状态：pending/processing/sent/part_failed/failed */
    private String status;

    /** 匹配的路由规则ID */
    private Long ruleId;

    /** 匹配的路由规则名称 */
    private String ruleName;

    /** 已重试次数 */
    private Integer retryCount;

    /** 非表字段：通知日志数（列表回显） */
    @TableField(exist = false)
    private Integer logCount;
}
