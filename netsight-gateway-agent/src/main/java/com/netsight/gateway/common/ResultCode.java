package com.netsight.gateway.common;

/**
 * 业务码定义（本地管理接口使用）。
 */
public final class ResultCode {

    public static final int SUCCESS = 200;
    /** 未登录或会话失效 */
    public static final int UNAUTHORIZED = 401;
    /** 用户名或密码错误 */
    public static final int BAD_CREDENTIALS = 4001;
    /** 需修改默认密码后再操作 */
    public static final int NEED_CHANGE_PWD = 4002;
    /** 操作失败（重启/同步等） */
    public static final int OPERATION_FAILED = 4003;
    /** 参数错误 */
    public static final int BAD_REQUEST = 4004;
    /** 登录尝试过于频繁 */
    public static final int RATE_LIMIT = 4005;

    private ResultCode() {
    }
}
