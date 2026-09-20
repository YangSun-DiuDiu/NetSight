package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知路由规则（自动发送策略）
 * 管理员配置：事件类型 → 触发条件 → 接收人策略 → 通道列表 → 消息模板
 * 事件中心按 event_type 匹配规则，实现业务与通道解耦
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_rule")
public class NotificationRule extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 规则名称 */
    private String ruleName;

    /** 关联事件类型 */
    private String eventType;

    /** 触发条件表达式 JSON（如 {"severity":"critical"}，空=全部匹配） */
    private String conditionJson;

    /** 接收人策略 JSON（如 {"type":"fixed","receivers":["138xxxx"]}） */
    private String receiverStrategyJson;

    /** 通道列表 JSON 数组（如 ["sms","wechat"]） */
    private String channelsJson;

    /** 关联消息模板ID */
    private Long templateId;

    /** 是否启用：1启用/0停用 */
    private Integer enabled;
}
