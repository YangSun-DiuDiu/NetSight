package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知消息模板
 * 各通道（sms/wechat）内容模板，支持 {{var}} 占位符由事件变量渲染
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notification_template")
public class NotificationTemplate extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 模板编码（租户内唯一，如 tpl_device_offline） */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 适用通道类型：sms / wechat / pushplus（类型兜底；channelId 为空时按此匹配默认模板） */
    private String channelType;

    /** 绑定的具体通道实例ID（notify_channel.id；方案B：模板直接绑定已配好参数的通道实例；为空=该类型默认模板） */
    private Long channelId;

    /** 模板内容（{{device_name}} 占位符） */
    private String content;

    /** 是否启用 */
    private Integer enabled;
}
