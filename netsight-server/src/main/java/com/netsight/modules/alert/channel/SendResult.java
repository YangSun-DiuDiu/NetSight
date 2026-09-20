package com.netsight.modules.alert.channel;

import lombok.Builder;
import lombok.Data;

/**
 * 统一发送结果对象（通道适配器 → 通道中心）
 */
@Data
@Builder
public class SendResult {

    /** 是否发送成功 */
    private boolean success;

    /** 失败原因 */
    private String errorMsg;

    /** 第三方通道返回的消息 ID */
    private String thirdPartyMsgId;

    /** 发送耗时 ms */
    private long costTime;
}
