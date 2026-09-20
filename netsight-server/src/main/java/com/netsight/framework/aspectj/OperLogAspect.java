package com.netsight.framework.aspectj;

import com.netsight.common.core.R;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.service.EventCenterService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志切面（方案 2.3）
 * <p>
 * 拦截所有标注 {@link Log} 注解的 Controller 写操作方法，记录操作人、模块、操作、请求参数（脱敏）、
 * IP、结果、耗时，复用事件中心 event_record 表（event_type = user_operation）统一存储，
 * 与告警事件同表查询，构成"告警发生→管理员处理→工单派发→备件领用"完整审计链路。
 * 切面记录失败不影响主业务（try-catch 兜底 + 仅 error 日志）。
 */
@Slf4j
@Aspect
@Component
@Order(1)
@RequiredArgsConstructor
public class OperLogAspect {

    /** 操作日志事件类型（方案约定：人工操作作为事件的一种，event_type = user_operation） */
    public static final String EVENT_TYPE_OPERATION = "user_operation";

    private static final int MAX_PARAM_LENGTH = 500;

    private final EventCenterService eventCenterService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(logAnno)")
    public Object around(ProceedingJoinPoint pjp, Log logAnno) throws Throwable {
        long start = System.currentTimeMillis();
        String result = "成功";
        try {
            Object ret = pjp.proceed();
            // 业务返回 R 但 code != 200（如库存不足等 Service 内兜底返回），视为业务失败
            if (ret instanceof R<?> r && r.getCode() != 200) {
                result = "失败：" + (StringUtils.hasText(r.getMsg()) ? r.getMsg() : "code=" + r.getCode());
            }
            return ret;
        } catch (Throwable e) {
            result = "失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        } finally {
            long costTime = System.currentTimeMillis() - start;
            try {
                record(pjp, logAnno, result, costTime);
            } catch (Exception e) {
                log.error("操作日志记录失败 module={} action={}: {}", logAnno.module(), logAnno.action(), e.getMessage());
            }
        }
    }

    /**
     * 组装操作事件并写入事件中心（event_record 表）
     */
    private void record(ProceedingJoinPoint pjp, Log log, String result, long costTime) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String methodName = pjp.getTarget().getClass().getSimpleName() + "." + signature.getMethod().getName();

        HttpServletRequest request = currentRequest();
        String uri = request == null ? "" : request.getRequestURI();
        String ip = request == null ? "" : getClientIp(request);
        String operator = currentOperator();
        String params = maskAndTruncate(pjp.getArgs());

        String content = String.format("操作人：%s 操作：%s 参数：%s IP：%s 结果：%s 耗时：%dms",
                operator, log.action(), params, ip, result, costTime);

        Map<String, Object> labels = new HashMap<>();
        labels.put("operator", operator);
        labels.put("module", log.module());
        labels.put("action", log.action());
        labels.put("method", methodName);
        labels.put("uri", uri);
        labels.put("ip", ip);
        labels.put("result", result);
        labels.put("costTime", costTime);

        EventRecord event = new EventRecord();
        // 未登录场景（登录接口本身/网关等）无租户上下文，兜底默认租户 1，避免 tenant_id=0 落库
        Long tenantId = SecurityUtils.getTenantId();
        event.setTenantId(tenantId != null && tenantId > 0 ? tenantId : 1L);
        event.setEventType(EVENT_TYPE_OPERATION);
        event.setEventSource("system");
        event.setSeverity("info");
        // 设备名字段承载模块名，便于事件中心列表直接展示操作模块
        event.setDeviceName(log.module());
        event.setDeviceIp(ip);
        event.setLocation(uri);
        event.setContent(content);
        try {
            event.setLabelsJson(objectMapper.writeValueAsString(labels));
        } catch (Exception ignored) {
            // labels 序列化失败不阻断记录
        }
        eventCenterService.receiveEvent(event);
    }

    /**
     * 当前登录用户名，未登录/匿名返回"匿名用户"
     */
    private String currentOperator() {
        try {
            if (SecurityUtils.isAuthenticated() && SecurityUtils.getLoginUser() != null
                    && StringUtils.hasText(SecurityUtils.getLoginUser().getUsername())) {
                return SecurityUtils.getLoginUser().getUsername();
            }
        } catch (Exception ignored) {
            // 未登录场景（登录接口本身）兜底
        }
        return "匿名用户";
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    /**
     * 获取客户端 IP：X-Forwarded-For（Nginx 反代）→ X-Real-IP → RemoteAddr
     */
    /**
     * 客户端真实 IP（审计用，防伪造）：X-Real-IP 优先（Nginx 首跳写入可信），
     * 其次 X-Forwarded-For 最右段（Nginx $proxy_add_x_forwarded_for 追加真实来源 IP 到末尾；
     * 取首段是客户端可伪造的任意值），最后 RemoteAddr 兜底。
     */
    private String getClientIp(HttpServletRequest request) {
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

    /**
     * 参数序列化：敏感字段（密码/密钥/Token/验证码）脱敏 + 超长截断，避免审计日志泄露凭据
     */
    private String maskAndTruncate(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(args);
            // 脱敏：password/secret/token（含 webhookToken/pushplusToken/gatewayToken 等驼峰变体）/smsCode/authorization
            // 等字段值替换为 ***。(?i) 不区分大小写 + 两端边界，覆盖 JSON 中各类大小写与驼峰键名，
            // 防止 gatewayToken/pushplusToken/webhookToken 等大写变体绕过脱敏导致 Token 明文落审计日志。
            json = json.replaceAll("(?i)(\"(?:password|oldpassword|newpassword|secret|token|accesstoken|authorization|webhooktoken|pushplustoken|gatewaytoken|smscode)\"\\s*:\\s*\")[^\"]*(\")", "$1***$2");
            if (json.length() > MAX_PARAM_LENGTH) {
                return json.substring(0, MAX_PARAM_LENGTH) + "...(已截断)";
            }
            return json;
        } catch (Exception e) {
            return "(参数序列化失败)";
        }
    }
}
