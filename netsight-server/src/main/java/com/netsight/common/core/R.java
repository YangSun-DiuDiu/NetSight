package com.netsight.common.core;

import lombok.Data;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结果封装
 *
 * @param <T> 数据类型
 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态码：200成功，401未登录，403无权限，500系统错误 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    /** 请求追踪ID（链路排查用） */
    private String traceId;

    public static <T> R<T> ok() {
        return build(200, "操作成功", null);
    }

    public static <T> R<T> ok(T data) {
        return build(200, "操作成功", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return build(200, msg, data);
    }

    public static <T> R<T> fail(String msg) {
        return build(500, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return build(resultCode.getCode(), resultCode.getMessage(), null);
    }

    private static <T> R<T> build(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        // TraceId 回填：由 TraceIdFilter 写入 MDC（异步线程/未经过过滤器时为 null）
        r.setTraceId(MDC.get("traceId"));
        return r;
    }
}
