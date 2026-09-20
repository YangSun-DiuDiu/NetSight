package com.netsight.framework.aspectj;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.time.Duration;

/**
 * 接口限流切面（方案 2.3）
 * <p>
 * 拦截所有标注 {@link RateLimit} 注解的方法，按 key 维度（IP / 手机号 / 自定义参数）基于 Redis
 * 计数器限流：key = {@code netsight:rate:limit:{维度}:{维度值}:{方法签名}:{窗口秒}}，
 * 窗口内计数超过 {@code limit} 抛 {@link ServiceException}(5006) 拒绝请求。
 * <p>
 * 说明：
 * <ul>
 *   <li>使用 Redis INCR 原子自增，首次自增后设置窗口过期时间，无并发竞态；</li>
 *   <li>只对标注注解的方法生效，未标注接口不受影响；</li>
 *   <li>限流拦截失败（Redis 异常）不阻断业务——降级放行并记录 error 日志（限流是防护手段，不应成为可用性单点）。</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final String RATE_LIMIT_KEY_PREFIX = "netsight:rate:limit:";
    /** 未配置 key 时默认按 IP 维度 */
    private static final String DEFAULT_DIMENSION = "ip";

    private static final DefaultParameterNameDiscoverer PARAM_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate redisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String[] dimensions = parseDimensions(rateLimit.key());
        String methodKey = buildMethodKey(pjp);
        // 任一维度超限即拒绝（同一请求内多维度都计数）
        for (String dimension : dimensions) {
            String dimensionValue = resolveDimensionValue(pjp, dimension.trim());
            if (!StringUtils.hasText(dimensionValue)) {
                continue;
            }
            String redisKey = RATE_LIMIT_KEY_PREFIX + dimension + ":" + dimensionValue + ":" + methodKey
                    + ":" + rateLimit.window();
            if (exceedLimit(redisKey, rateLimit.limit(), rateLimit.window())) {
                String message = StringUtils.hasText(rateLimit.message())
                        ? rateLimit.message() : ResultCode.RATE_LIMIT_EXCEEDED.getMessage();
                log.warn("接口限流触发 维度={} 值={} 方法={} limit={}/{}s", dimension, dimensionValue, methodKey,
                        rateLimit.limit(), rateLimit.window());
                throw new ServiceException(ResultCode.RATE_LIMIT_EXCEEDED.getCode(), message);
            }
        }
        return pjp.proceed();
    }

    /**
     * 解析维度：逗号分隔；空串回退默认 IP
     */
    private String[] parseDimensions(String key) {
        if (!StringUtils.hasText(key)) {
            return new String[]{DEFAULT_DIMENSION};
        }
        return key.split(",");
    }

    /**
     * 方法签名标识（类名.方法名），不同接口独立计数
     */
    private String buildMethodKey(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        return pjp.getTarget().getClass().getSimpleName() + "." + signature.getMethod().getName();
    }

    /**
     * Redis 计数器：INCR 后若为 1 设置窗口过期；计数 &gt; limit 返回 true（超限）
     */
    private boolean exceedLimit(String redisKey, int limit, int window) {
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null || count <= 0) {
                return false;
            }
            if (count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(window));
            }
            return count > limit;
        } catch (Exception e) {
            // Redis 不可用时降级放行，限流不应成为可用性单点
            log.error("限流计数失败，降级放行 key={}: {}", redisKey, e.getMessage());
            return false;
        }
    }

    /**
     * 解析维度值：
     * <ul>
     *   <li>ip → 客户端 IP（X-Forwarded-For → X-Real-IP → RemoteAddr）</li>
     *   <li>其它 → 从方法参数中按参数名 / 对象属性名取值（如 phone）</li>
     * </ul>
     */
    private String resolveDimensionValue(ProceedingJoinPoint pjp, String dimension) {
        if (DEFAULT_DIMENSION.equals(dimension)) {
            return getClientIp();
        }
        return resolveParamValue(pjp, dimension);
    }

    private String resolveParamValue(ProceedingJoinPoint pjp, String name) {
        Object[] args = pjp.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] paramNames = PARAM_NAME_DISCOVERER.getParameterNames(signature.getMethod());
        java.lang.annotation.Annotation[][] paramAnnotations = signature.getMethod().getParameterAnnotations();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                continue;
            }
            // 1. @RequestParam 注解显式声明的参数名（不依赖 -parameters 编译参数）
            if (paramAnnotations != null && i < paramAnnotations.length) {
                String requestParamName = requestParamName(paramAnnotations[i]);
                if (requestParamName != null && name.equals(requestParamName)) {
                    return String.valueOf(arg);
                }
            }
            // 2. 反射参数名匹配（@RequestParam 等简单参数）
            if (paramNames != null && i < paramNames.length && name.equals(paramNames[i])) {
                return String.valueOf(arg);
            }
            // 3. 对象属性匹配（@RequestBody 实体的 phone 字段）
            String fieldValue = readField(arg, name);
            if (fieldValue != null) {
                return fieldValue;
            }
        }
        return null;
    }

    private String requestParamName(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation ann : annotations) {
            if (ann instanceof org.springframework.web.bind.annotation.RequestParam rp) {
                if (StringUtils.hasText(rp.value())) {
                    return rp.value();
                }
                if (StringUtils.hasText(rp.name())) {
                    return rp.name();
                }
            }
        }
        return null;
    }

    private String readField(Object obj, String fieldName) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null && clazz != Object.class) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    return value == null ? null : String.valueOf(value);
                } catch (NoSuchFieldException ignored) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
            // 取不到该字段忽略
        }
        return null;
    }

    /**
     * 获取客户端真实 IP（防伪造限流绕过/审计失真）：
     * 优先级 ① X-Real-IP（Nginx 首跳写入，可信）→ ② X-Forwarded-For 最右段
     * （Nginx $proxy_add_x_forwarded_for 会**追加**真实来源 IP 到末尾；取首段是客户端可伪造的
     * 任意值，攻击者可随意切换 IP 绕过限流）→ ③ RemoteAddr（直连兜底）。
     * 前提：Nginx 反代配置了 X-Real-IP / proxy_add_x_forwarded_for（nams.conf 已配置）。
     */
    private String getClientIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "";
        }
        HttpServletRequest request = attrs.getRequest();
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp) && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !"unknown".equalsIgnoreCase(forwarded)) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}
