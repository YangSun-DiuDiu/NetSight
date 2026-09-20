package com.netsight.common.core;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码定义
 * 200成功 / 401未登录 / 403无权限 / 500系统错误
 * 1xxx设备 / 2xxx工单 / 3xxx备件 / 4xxx通知 / 5xxx系统管理
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),
    /** 未登录或Token过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 无权限 */
    FORBIDDEN(403, "没有权限访问该资源"),
    /** 系统内部错误 */
    ERROR(500, "系统内部错误"),
    /** 参数校验失败 */
    PARAM_ERROR(400, "参数校验失败"),

    // ===== 1xxx 设备/资产模块 =====
    DEVICE_NOT_FOUND(1001, "设备不存在"),
    DEVICE_CODE_EXIST(1002, "设备编码已存在"),
    DEVICE_IP_EXIST(1003, "设备IP已存在"),
    GATEWAY_NOT_FOUND(1004, "网关不存在"),
    GATEWAY_TOKEN_INVALID(1005, "网关Token无效或网关已禁用"),

    // ===== 2xxx 工单/维修模块 =====
    ORDER_NOT_FOUND(2001, "工单不存在"),
    ORDER_STATUS_ERROR(2002, "工单状态不允许该操作"),

    // ===== 3xxx 备件模块 =====
    SPARE_NOT_FOUND(3001, "备件不存在"),
    SPARE_STOCK_NOT_ENOUGH(3002, "备件库存不足"),

    // ===== 4xxx 通知/事件模块 =====
    EVENT_NOT_FOUND(4001, "事件不存在"),
    CHANNEL_NOT_SUPPORT(4002, "通知通道不存在或未启用"),

    // ===== 5xxx 系统管理模块 =====
    USER_NOT_FOUND(5001, "用户不存在"),
    USER_DISABLED(5002, "用户已被禁用"),
    VERIFY_CODE_ERROR(5003, "验证码错误或已过期"),
    PASSWORD_ERROR(5004, "手机号或验证码错误"),
    TENANT_NOT_FOUND(5005, "租户不存在"),
    /** 接口限流触发（方案 2.3 @RateLimit） */
    RATE_LIMIT_EXCEEDED(5006, "请求过于频繁，请稍后再试");

    private final int code;
    private final String message;
}
