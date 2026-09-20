package com.netsight.common.exception;

import com.netsight.common.core.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 * 业务逻辑校验失败时抛出，由全局异常处理器统一转换为友好提示
 */
@Getter
public class ServiceException extends RuntimeException {

    private final int code;

    public ServiceException(String message) {
        super(message);
        this.code = 500;
    }

    public ServiceException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }
}
