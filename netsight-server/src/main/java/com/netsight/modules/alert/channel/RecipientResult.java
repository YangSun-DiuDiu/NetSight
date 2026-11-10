package com.netsight.modules.alert.channel;

import lombok.Builder;
import lombok.Data;

/**
 * 单个接收人的发送结果（微信公众号逐人发送时使用）。
 * receiver 必须是脱敏后的标识，禁止把完整 openid 写入日志/接口返回值。
 */
@Data
@Builder
public class RecipientResult {

    /** 脱敏后的接收人标识 */
    private String receiver;

    /** 是否成功 */
    private boolean success;

    /** 微信错误码（成功为 0） */
    private Integer errcode;

    /** 微信错误信息 / 成功为空 */
    private String errmsg;

    /** 该接收人实际重试次数（不含首次） */
    private int retryCount;

    /** 是否被跳过（去重 / 频控 / 未绑定 / appId 不匹配） */
    private boolean skipped;

    /** 跳过原因：duplicate / rate_limit / app_id_mismatch / unbound */
    private String skipReason;
}
