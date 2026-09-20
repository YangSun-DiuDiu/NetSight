package com.netsight.modules.alert.controller;

import com.netsight.common.core.R;
import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.service.AlertPushService;
import com.netsight.modules.system.service.WebhookTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AlertManager Webhook 接入控制器（云端接入点，免用户认证）
 *
 * 边缘网关本地 AlertManager 将告警推送到此接口：
 *   POST /alert/push
 *   Header: X-Netsight-Webhook-Token: <租户级 Token 或过渡期全局密钥>（防伪造）
 *   Header: X-Netsight-Tenant-Id: <事件归属租户ID>（仅过渡兼容，Token 命中以 Token 为准）
 *
 * 鉴权信任模型（方案 5.5 第 7 小节，V1.1.6）：
 *   ① 租户级 Token 优先：服务端反查（Redis → sys_tenant.webhook_token）解析 tenant_id，
 *      Token 即租户身份，事件全部归属该租户——不再信任客户端自报租户；
 *   ② 过渡兼容：未命中租户 Token 时，回退全局 webhook-token + Tenant-Id 头（仅本地开发/迁移期）；
 *   ③ 均失败 → 401 拒绝，告警日志记录来源 IP。
 *
 * 报文兼容 AlertManager v4（alerts[]）与方案标准事件格式（event_type）
 */
@Slf4j
@RestController
@RequestMapping("/alert/push")
@RequiredArgsConstructor
public class AlertPushController {

    private final AlertPushService alertPushService;
    private final WebhookTokenService webhookTokenService;

    /** 全局接入密钥（仅过渡兼容/本地开发兜底；生产多租户请使用租户级 Token） */
    @Value("${netsight.alert.webhook-token:}")
    private String webhookToken;

    /**
     * 【安全加固】全局 Webhook Token 启动校验：
     * 生产（默认 profile）无默认值——未通过环境变量 WEBHOOK_TOKEN 注入时直接拒绝启动，
     * 防止默认密钥被用于伪造任意租户告警；开发（dev profile）由 application-dev.yml 提供默认值，不受影响。
     */
    @jakarta.annotation.PostConstruct
    public void validateWebhookToken() {
        if (!StringUtils.hasText(webhookToken)) {
            throw new IllegalStateException(
                    "WEBHOOK_TOKEN 未配置：请通过环境变量注入全局 Webhook Token（生产多租户环境推荐全部使用租户级 Token）");
        }
    }

    /**
     * Webhook 推送入口（免登录，走 Header 密钥鉴权）
     */
    @PostMapping
    public R<List<Long>> push(@RequestHeader(value = "X-Netsight-Webhook-Token", required = false) String token,
                              @RequestHeader(value = "X-Netsight-Tenant-Id", required = false) Long tenantId,
                              @RequestBody Map<String, Object> body,
                              HttpServletRequest request) {
        // ① 租户级 Token 优先：服务端反查（Redis → DB → 回填缓存）
        Long resolvedTenant = webhookTokenService.resolveTenantId(token);
        if (resolvedTenant != null) {
            return R.ok("事件已接入", alertPushService.push(body, resolvedTenant));
        }

        // ② 过渡兼容：全局 webhook-token（本地开发 / 存量网关迁移期）→ 使用 Tenant-Id 头（缺省 1）
        if (webhookToken.equals(token)) {
            Long targetTenant = tenantId == null ? 1L : tenantId;
            log.warn("Webhook 使用全局密钥过渡鉴权（租户={}，IP={}），建议切换租户级 Token", targetTenant, clientIp(request));
            return R.ok("事件已接入", alertPushService.push(body, targetTenant));
        }

        // ③ 拒绝：Token 无效（含租户已禁用），记录来源 IP 便于审计
        log.warn("Webhook 接入密钥校验失败，拒绝报文（IP={}）", clientIp(request));
        throw new ServiceException(401, "Webhook 接入密钥校验失败");
    }

    /**
     * 客户端真实 IP（Nginx 反代链路：X-Forwarded-For → X-Real-IP → RemoteAddr）
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
