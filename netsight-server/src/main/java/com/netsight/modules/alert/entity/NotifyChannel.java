package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知渠道实例（渠道管理页维护）
 * 一行 = 一个渠道实例；同一渠道类型可配多个实例（如多个钉钉机器人群）。
 * config_json 存该实例的通道参数（密钥类已 AES 加密），发送时由通道中心注入 SendRequest.channelConfig。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notify_channel")
public class NotifyChannel extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 渠道类型：aliyun_sms/tencent_sms/dingtalk/wechat_work/wechat_app/feishu/serverchan/pushplus/email/webhook */
    private String channelType;

    /** 实例名称（如"厂区钉钉告警群"） */
    private String channelName;

    /** 渠道参数 JSON（密钥类已加密） */
    private String configJson;

    /** 启用：1启用/0停用 */
    private Integer enabled;

    /** 健康状态：unknown/healthy/down */
    private String healthStatus;

    /** 最近健康检查时间 */
    private java.util.Date lastCheckTime;

    /** 备注 */
    private String remark;
}
