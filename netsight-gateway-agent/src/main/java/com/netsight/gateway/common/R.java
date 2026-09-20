package com.netsight.gateway.common;

import lombok.Data;

/**
 * 统一返回体（与云端 netsight-common 风格一致）。
 */
@Data
public class R<T> {

    /** 业务码：200 成功，其余见 {@link ResultCode} */
    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(ResultCode.SUCCESS);
        r.setMsg("success");
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
