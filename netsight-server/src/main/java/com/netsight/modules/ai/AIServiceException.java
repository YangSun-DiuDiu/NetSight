package com.netsight.modules.ai;

/**
 * AI 服务异常
 * 模型调用失败（网络/超时/返回异常/额度不足）统一包装，由应用层降级处理
 */
public class AIServiceException extends RuntimeException {

    public AIServiceException(String message) {
        super(message);
    }

    public AIServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
