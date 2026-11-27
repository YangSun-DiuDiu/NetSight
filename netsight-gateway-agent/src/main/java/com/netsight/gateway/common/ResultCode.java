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

    /** 视频：设备未配置 RTSP 接入 */
    public static final int VIDEO_NOT_CONFIGURED = 4101;
    /** 视频：RTSP 源不可达 */
    public static final int VIDEO_SOURCE_UNREACHABLE = 4102;
    /** 视频：摄像头认证失败 */
    public static final int VIDEO_AUTH_FAILED = 4103;
    /** 视频：ZLM 媒体服务不可用 */
    public static final int VIDEO_ZLM_UNAVAILABLE = 4104;
    /** 视频：并发路数已满 */
    public static final int VIDEO_MAX_SESSIONS = 4105;
    /** 视频：拉流启动超时 */
    public static final int VIDEO_START_TIMEOUT = 4106;

    private ResultCode() {
    }
}
