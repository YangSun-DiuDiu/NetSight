package com.netsight.modules.alert.channel;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 统一发送请求对象（通道中心 → 通道适配器）
 * 所有通道共用，新增通道不得修改本模型
 */
@Data
@Builder
public class SendRequest {

    /** 接收人列表（手机号 / OpenID / 邮箱地址） */
    private List<String> receiverList;

    /** 内容模板ID（元数据，追溯用） */
    private Long templateId;

    /** 模板变量 Map（渲染用） */
    private Map<String, Object> contentVars;

    /** 已渲染好的通知内容（通道中心渲染后传入，适配器直接发送） */
    private String content;

    /** 关联业务 ID（去重和追溯） */
    private String bizId;

    /** 所属租户ID（PushPlus 等按租户取密钥的通道使用；通道中心从事件租户透传） */
    private Long tenantId;

    /** 来源网关编码（PushPlus 等支持网关级密钥的通道使用；通道中心从事件 labels 解析，可为空） */
    private String gatewayCode;

    /** 扩展字段（如公众号跳转 URL、邮件附件） */
    private Map<String, Object> extra;

    /**
     * 渠道实例配置（本次发送的 notify_channel.config_json 解析结果）。
     * Sender 从这里读 API Key/Secret/Webhook 地址等参数，不再硬编码。
     * 由通道中心 dispatch 时按渠道实例 ID 查询后注入。
     */
    private Map<String, Object> channelConfig;
}
