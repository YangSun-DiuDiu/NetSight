package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知发送日志
 * 每次通道发送的记录（事件/规则/通道/接收人/内容/结果），支持审计查询与失败告警
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_log")
public class NotificationLog extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 关联事件ID */
    private Long eventId;

    /** 关联规则ID */
    private Long ruleId;

    /** 规则名称（快照） */
    private String ruleName;

    /** 事件类型（快照） */
    private String eventType;

    /** 发送通道：sms / wechat */
    private String channelType;

    /** 接收人列表 JSON */
    private String receiversJson;

    /** 实际发送内容 */
    private String content;

    /** 业务ID（去重/追溯） */
    private String bizId;

    /** 是否成功：1成功/0失败 */
    private Integer success;

    /** 失败原因 */
    private String errorMsg;

    /** 第三方通道返回的消息ID */
    private String thirdPartyMsgId;

    /** 发送耗时 ms */
    private Long costTime;

    /** 重试次数 */
    private Integer retryCount;
}
