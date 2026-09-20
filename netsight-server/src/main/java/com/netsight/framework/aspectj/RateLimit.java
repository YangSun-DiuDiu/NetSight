package com.netsight.framework.aspectj;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（方案 2.3）
 * <p>
 * 标注在 Controller 方法上，由 {@link RateLimitAspect} 拦截，基于 Redis 计数器
 * 对高频接口（短信验证码、登录等）做限流，防止被恶意刷接口。
 * <p>
 * 限流维度由 {@link #key()} 指定，支持逗号分隔多个维度，任一维度超限即拒绝：
 * <ul>
 *   <li>{@code ip}：按客户端 IP 维度（默认）</li>
 *   <li>{@code phone}：按方法参数中的手机号字段维度（支持 @RequestParam 或 @RequestBody 对象属性）</li>
 *   <li>其它参数名：从方法参数中按名提取（参数名或对象属性）</li>
 * </ul>
 * <pre>
 * 示例：
 * &#64;RateLimit(limit = 5, window = 60)                       // 60 秒内同 IP 最多 5 次
 * &#64;RateLimit(key = "phone,ip", limit = 1, window = 60)      // 60 秒内同手机号或同 IP 最多 1 次
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 限流维度，逗号分隔多个维度；空/未指定 = 按 IP */
    String key() default "";

    /** 窗口内最大请求次数（含），超过即拒绝 */
    int limit() default 10;

    /** 时间窗口（秒） */
    int window() default 60;

    /** 超限提示语，默认"请求过于频繁，请稍后再试" */
    String message() default "";
}
