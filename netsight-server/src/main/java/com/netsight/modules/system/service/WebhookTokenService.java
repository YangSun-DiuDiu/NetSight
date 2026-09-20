package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.util.TokenCrypto;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 租户级 Webhook Token 服务（方案 5.5 第 7 小节落地）
 *
 * 核心原则：<b>Token 即租户身份，服务端权威反查</b>——谁持有某租户的 Token，
 * 上报的事件就归属该租户，不再信任客户端自报租户（X-Netsight-Tenant-Id 仅过渡兼容）。
 *
 * 职责：
 * 1. Token 生成 / 查看 / 重置（租户管理域，超管专用）
 * 2. Token → tenantId 反查（AlertManager Webhook 鉴权：Redis 缓存 → DB 兜底 → 回填缓存）
 *
 * 与网关 Token 职责分离：网关 Token（X-Gateway-Token）管 /edge/report/* 设备上报，
 * 租户 Webhook Token 管 /alert/push 告警接入，两者统一"凭证绑定租户、服务端反查"信任模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookTokenService {

    /** Redis 缓存前缀：token → tenantId（value 为租户ID字符串） */
    private static final String TOKEN_KEY_PREFIX = "netsight:webhook:token:";

    /** 缓存 TTL：24 小时；DB 始终兜底，重置/租户禁用时主动删除缓存 */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final SysTenantMapper sysTenantMapper;
    private final StringRedisTemplate redisTemplate;
    private final TokenCrypto tokenCrypto;

    /**
     * 生成新 Token（UUID 去横线 32 位，与网关 Token 生成规则一致）
     */
    public String genToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 新增租户时初始化 Token（若调用方未预置则自动生成）
     *
     * @return Token 明文（调用方用于返回展示；落库值已加密）
     */
    public String initToken(SysTenant tenant) {
        if (tenant == null) {
            return null;
        }
        String plain = tenant.getWebhookToken();
        if (!StringUtils.hasText(plain)) {
            plain = genToken();
        }
        // 预置明文也统一加密落库，避免绕过加密
        tenant.setWebhookToken(tokenCrypto.encrypt(plain));
        tenant.setWebhookTokenTime(LocalDateTime.now());
        return plain;
    }

    /**
     * 查看租户 Webhook Token（明文，超管专用；库内密文解密返回）
     */
    public String getToken(Long tenantId) {
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        return tokenCrypto.decrypt(tenant.getWebhookToken());
    }

    /**
     * 重置租户 Webhook Token：新 Token 落库（密文）+ 旧 Token 缓存立即失效（DB + Redis 双清）
     *
     * @return 新 Token（明文）
     */
    public String resetToken(Long tenantId) {
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        // 旧 Token 密文解密后再清理缓存（缓存 key 为明文 token）
        String oldToken = tokenCrypto.decrypt(tenant.getWebhookToken());
        String newToken = genToken();

        SysTenant update = new SysTenant();
        update.setId(tenantId);
        update.setWebhookToken(tokenCrypto.encrypt(newToken));
        update.setWebhookTokenTime(LocalDateTime.now());
        sysTenantMapper.updateById(update);

        // 旧 Token 立即失效（若缓存中存在新 token 也一并清理，防止脏数据）
        evictCache(oldToken);
        evictCache(newToken);
        log.info("租户[{}] Webhook Token 已重置，旧 Token 已失效", tenantId);
        return newToken;
    }

    /**
     * ▶ 核心：Token → tenantId 反查（Webhook 接入鉴权，服务端权威）
     *
     * 顺序：① Redis 缓存命中 → 直接返回；② DB 反查（webhook_token 匹配 + 租户启用）→ 回填缓存；
     * 均未命中返回 null（调用方应 401 拒绝）。
     *
     * @param token 请求头 X-Netsight-Webhook-Token 值
     * @return 租户ID；无效 Token 返回 null
     */
    public Long resolveTenantId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String cacheKey = TOKEN_KEY_PREFIX + token;

        // ① Redis 缓存命中
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return Long.valueOf(cached);
            }
        } catch (Exception e) {
            // 缓存异常不阻断鉴权（降级查库），限流/缓存是优化手段不是安全边界
            log.warn("Webhook Token 缓存读取异常（降级查库）: {}", e.getMessage());
        }

        // ② DB 反查（sys_tenant 为多租户忽略表，天然不受租户过滤；仅启用租户有效）
        // 库内为 AES-256-GCM 密文（随机 IV，密文不可预测），无法用等值 SQL 匹配，
        // 采用全量拉取 + 内存解密比对（租户量级小，可忽略开销；避免引入哈希索引列）
        List<SysTenant> tenants = sysTenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getStatus, 1));
        for (SysTenant t : tenants) {
            if (StringUtils.hasText(t.getWebhookToken())
                    && token.equals(tokenCrypto.decrypt(t.getWebhookToken()))) {
                // ③ 回填缓存
                try {
                    redisTemplate.opsForValue().set(cacheKey, String.valueOf(t.getId()), CACHE_TTL);
                } catch (Exception e) {
                    log.warn("Webhook Token 缓存回填异常: {}", e.getMessage());
                }
                return t.getId();
            }
        }
        return null;
    }

    /**
     * 删除 Token 缓存（重置 / 租户禁用时调用，使 Token 立即失效）
     */
    public void evictCache(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        try {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
        } catch (Exception e) {
            log.warn("Webhook Token 缓存删除异常: {}", e.getMessage());
        }
    }
}
