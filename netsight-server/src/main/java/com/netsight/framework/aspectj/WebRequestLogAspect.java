package com.netsight.framework.aspectj;

import com.netsight.common.core.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Web 请求日志切面（方案 2.3）
 * 记录所有 Controller 接口的请求 URL、HTTP 方法、耗时、业务状态码，用于问题排查与接口性能监控。
 * 仅输出日志（配合 logback 落盘），不落库、不阻断业务。
 */
@Slf4j
@Aspect
@Component
@Order(2)
public class WebRequestLogAspect {

    @Around("execution(* com.netsight.modules..controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        HttpServletRequest request = currentRequest();
        String uri = request == null ? "-" : request.getRequestURI();
        String method = request == null ? "-" : request.getMethod();
        String clientIp = request == null ? "-" : clientIp(request);
        try {
            Object ret = pjp.proceed();
            long cost = System.currentTimeMillis() - start;
            int code = 200;
            if (ret instanceof R<?> r) {
                code = r.getCode();
            }
            log.info("HTTP {} {} ip={} code={} 耗时 {}ms", method, uri, clientIp, code, cost);
            return ret;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;
            log.error("HTTP {} {} ip={} 异常 耗时 {}ms: {}", method, uri, clientIp, cost,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }
}
