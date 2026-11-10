package com.netsight.modules.alert.wechat;

import lombok.Builder;
import lombok.Data;

/**
 * 微信绑定结果。绑定失败只影响微信触达，不影响登录/工单。
 */
@Data
@Builder
public class WechatBindResult {

    private boolean bound;
    private String reason;
    private String message;
}
