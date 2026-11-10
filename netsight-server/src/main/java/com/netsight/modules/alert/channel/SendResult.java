package com.netsight.modules.alert.channel;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 统一发送结果对象（通道适配器 → 通道中心）
 */
@Data
@Builder
public class SendResult {

    /** 是否发送成功 */
    private boolean success;

    /** 是否允许通道层整批重试；逐人发送的通道应置 false */
    @Builder.Default
    private boolean retryable = true;

    /** 实际使用的模板 ID（微信公众号） */
    private String templateId;

    /** 渲染后的可读内容摘要（写日志） */
    private String renderedContent;

    /** 逐人发送结果；非空时通道层不得整批重试 */
    private List<RecipientResult> recipientResults;

    /** 失败原因 */
    private String errorMsg;

    /** 第三方通道返回的消息 ID */
    private String thirdPartyMsgId;

    /** 发送耗时 ms */
    private long costTime;
}
