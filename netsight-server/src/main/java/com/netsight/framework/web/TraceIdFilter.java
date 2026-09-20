package com.netsight.framework.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪 ID 过滤器（TraceId）
 *
 * 职责：
 * 1. 请求入口生成/透传 TraceId：优先取请求头 X-Trace-Id（便于网关/上游链路透传），
 *    缺省生成 UUID（去横线 32 位）；
 * 2. 写入 MDC「traceId」，供日志（logback pattern 可引用 %X{traceId}）与统一响应
 *    R.traceId 回填使用，实现「一次请求一条链路 ID」的排查语义；
 * 3. 响应头 X-Trace-Id 回写，便于客户端/网关按同一 ID 检索日志；
 * 4. finally 清理 MDC，防止线程复用导致串号。
 *
 * 注册：@Component + @Order(HIGHEST_PRECEDENCE) 使其先于 Spring Security 链执行，
 * 保证认证失败等早期响应也能带上 TraceId。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
