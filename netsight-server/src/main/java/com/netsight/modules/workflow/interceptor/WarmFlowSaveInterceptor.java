package com.netsight.modules.workflow.interceptor;

import com.netsight.framework.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Warm-Flow 设计器保存拦截器
 *
 * 背景：Warm-Flow 1.8.9 的 TenantHandler 只在查询时追加租户过滤条件，
 * INSERT flow_definition 时不自动填充 tenant_id。设计器直接调 /warm-flow/save-json
 * 保存流程时，tenant_id 为 NULL，导致项目里看不到该流程。
 *
 * 方案：在保存请求完成后，根据当前登录用户的租户ID回填最新插入的 flow_definition 记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmFlowSaveInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        String path = request.getRequestURI();
        // 只处理设计器保存请求
        if (path == null || !path.contains("/warm-flow/save-json")) {
            return;
        }

        // 只处理成功的保存
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            log.warn("[WarmFlow-Save] 保存失败 status={}, 跳过 tenant_id 回填", response.getStatus());
            return;
        }

        // 获取当前租户ID
        Long tenantId = SecurityUtils.getTenantId();
        if (tenantId == null || tenantId == 0) {
            log.warn("[WarmFlow-Save] 当前未登录或租户ID为0，跳过 tenant_id 回填");
            return;
        }

        // 回填最新的 flow_definition 记录（10秒内创建且 tenant_id 为 NULL）
        try {
            int updated = jdbcTemplate.update(
                "UPDATE flow_definition SET tenant_id = ? " +
                "WHERE tenant_id IS NULL AND create_time >= DATE_SUB(NOW(), INTERVAL 10 SECOND)",
                String.valueOf(tenantId)
            );
            if (updated > 0) {
                log.info("[WarmFlow-Save] 回填 tenant_id={} 到 {} 条流程定义", tenantId, updated);
            }
        } catch (Exception e) {
            log.error("[WarmFlow-Save] 回填 tenant_id 失败", e);
        }
    }
}
