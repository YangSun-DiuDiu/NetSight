package com.netsight.modules.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.util.TokenCrypto;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import com.netsight.modules.system.service.PushplusTokenService;
import com.netsight.modules.system.service.WebhookTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;
import com.netsight.framework.aspectj.Log;

import java.util.Map;

/**
 * 租户管理接口（多租户隔离：sys_tenant 为系统公共表，豁免租户过滤；仅超管可管理）
 *
 * V1.1.6 新增：租户级 Webhook Token 管理——新增租户自动生成 Token，
 * 提供「查看明文 / 一键重置」接口（方案 5.5 第 7 小节）。
 * V1.1.7 新增：租户级 PushPlus Token 管理——配置 / 查看 / 清空
 * （方案 5.8 通道 SPI，通知由云端发出，密钥收口云端租户管理）。
 */
@RestController
@RequestMapping("/system/tenant")
@RequiredArgsConstructor
@Slf4j
public class SysTenantController {

    private final SysTenantMapper sysTenantMapper;
    private final WebhookTokenService webhookTokenService;
    private final PushplusTokenService pushplusTokenService;
    private final NotificationTemplateMapper notificationTemplateMapper;
    private final TokenCrypto tokenCrypto;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 含 tenant_id 的业务表清单（删除租户时级联物理清理，与「业务数据归属租户」一致）。
     * 注意：sys_role / sys_permission / sys_role_permission 为全局公共表（无 tenant_id）不删除；
     * sys_user / sys_user_role 单独按 tenant_id 清理（sys_user 无租户过滤注解，需显式条件）。
     */
    private static final String[] TENANT_SCOPE_TABLES = {
            "device_topology", "device", "edge_gateway",
            "event_record", "notification_log", "notification_rule", "notification_template",
            "notice_read", "notice",
            "work_order_part", "work_order_record", "work_order", "repairer",
            "spare_part_record", "spare_part",
            "fault_article",
            "inspection_record", "inspection_task", "inspection_plan", "inspection_item"
    };

    /**
     * 租户列表（分页；webhook_token / pushplus_token 脱敏展示，明文走查看接口）
     * 注：库内 Token 为密文（enc: 前缀），脱敏前必须先解密，否则掩码失去意义
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('super_admin')")
    public R<PageResult<SysTenant>> list(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) String tenantName) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<SysTenant>()
                .like(tenantName != null && !tenantName.isBlank(), SysTenant::getTenantName, tenantName)
                .orderByDesc(SysTenant::getCreateTime);
        Page<SysTenant> page = sysTenantMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        // 列表脱敏：只留前后片段，明文仅通过查看接口下发
        page.getRecords().forEach(t -> {
            t.setWebhookToken(maskToken(tokenCrypto.decrypt(t.getWebhookToken())));
            t.setPushplusToken(maskToken(tokenCrypto.decrypt(t.getPushplusToken())));
        });
        return R.ok(PageResult.of(page.getTotal(), page.getRecords()));
    }

    /**
     * 新增租户（自动生成 Webhook Token，返回一次性展示）
     *
     * @return 新租户 Webhook Token（明文；前端新增成功后弹窗提示并复制）
     */
    @PostMapping
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "新增租户")
    public R<String> add(@RequestBody SysTenant tenant) {
        // initToken 返回明文用于展示；落库值已加密
        String plainToken = webhookTokenService.initToken(tenant);
        // 【加固】显式置启用：未传 status 时默认为 1，防止 status=null 导致 Webhook Token 反查（status=1 条件）立即失效
        if (tenant.getStatus() == null) {
            tenant.setStatus(1);
        }
        sysTenantMapper.insert(tenant);
        // V1.1.10：新租户自动复制租户1（默认租户）的完整消息模板集，
        // 保证新租户通知开箱即含租户名等标准占位符，避免手工维护模板遗漏
        copyDefaultTemplates(tenant.getId());
        log.info("新增租户[{}]，已自动生成 Webhook Token 并复制默认消息模板", tenant.getId());
        return R.ok("租户已创建，Webhook Token 如下", plainToken);
    }

    /**
     * 复制租户1（默认租户）的全部消息模板到新租户（一条 INSERT...SELECT 原子完成，绕过租户拦截器）。
     * 模板变量命名契约：告警链路 {{tenant_name}}、派单链路 {{tenantName}}，
     * 默认模板内容已按该契约编写，复制即开箱可用；复制失败不阻断租户创建（日志告警）。
     */
    private void copyDefaultTemplates(Long newTenantId) {
        try {
            int copied = notificationTemplateMapper.copyTemplatesFromDefault(newTenantId);
            log.info("新租户[{}]已自动复制 {} 条默认消息模板", newTenantId, copied);
        } catch (Exception e) {
            log.error("新租户[{}]复制默认模板失败，请手动在消息模板页补齐: {}", newTenantId, e.getMessage());
        }
    }

    /**
     * 修改租户（禁止通过此接口修改 webhook_token，防止覆盖）
     */
    @PutMapping
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "修改租户")
    public R<Void> update(@RequestBody SysTenant tenant) {
        SysTenant old = tenant.getId() != null ? sysTenantMapper.selectById(tenant.getId()) : null;
        // 双保险：Webhook Token 变更只能走重置接口，此处强制置空防误覆盖
        tenant.setWebhookToken(null);
        tenant.setWebhookTokenTime(null);
        // PushPlus Token 变更只能走专用配置接口，此处强制置空防误覆盖
        tenant.setPushplusToken(null);
        tenant.setPushplusTokenTime(null);
        sysTenantMapper.updateById(tenant);
        // 租户被禁用（1→0）时 Webhook Token 立即失效：DB 层 resolveTenantId 已带 status=1，
        // 此处再清缓存，避免 Redis 残留映射在 TTL 内继续放行
        if (old != null && tenant.getStatus() != null && tenant.getStatus() == 0
                && old.getStatus() != null && old.getStatus() == 1) {
            // 缓存 key 为明文 token，库内为密文，必须先解密再清理
            webhookTokenService.evictCache(tokenCrypto.decrypt(old.getWebhookToken()));
        }
        return R.ok();
    }

    /**
     * 删除租户（事务内：① Token 失效（status=0 + 缓存清理）② 级联物理清理该租户业务数据）
     *
     * 级联范围（OCR 审查项 Medium#13-②）：
     * - 含 tenant_id 的全部业务表（TENANT_SCOPE_TABLES）物理删除；
     * - sys_user（按 tenant_id）与 sys_user_role（IN 子查询）清理；
     * - sys_role / sys_permission / sys_role_permission 为全局公共表，不删除。
     *
     * 安全保护：默认租户（id=1）禁止删除，防止误删清空全部演示/生产数据。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "删除租户")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> delete(@PathVariable Long id) {
        if (id != null && id == 1L) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "默认租户禁止删除");
        }
        SysTenant tenant = sysTenantMapper.selectById(id);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        // ① Token 立即失效：先置 status=0（反查 status=1 条件失效）+ 缓存清理（key 为明文，先解密）
        webhookTokenService.evictCache(tokenCrypto.decrypt(tenant.getWebhookToken()));
        SysTenant disable = new SysTenant();
        disable.setId(id);
        disable.setStatus(0);
        sysTenantMapper.updateById(disable);
        // ② 级联物理清理该租户业务数据（先子表后主表；无外键约束，顺序仅保证语义正确）
        for (String table : TENANT_SCOPE_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE tenant_id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE tenant_id = ?)", id);
        jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id = ?", id);
        // ③ 逻辑删除租户本身
        sysTenantMapper.deleteById(id);
        log.info("租户[{}]已删除，业务数据（含用户）已级联清理", id);
        return R.ok();
    }

    /**
     * 查看租户 Webhook Token（明文，超管专用）
     */
    @GetMapping("/{id}/webhook-token")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "查看WebhookToken")
    public R<String> getWebhookToken(@PathVariable Long id) {
        return R.ok(webhookTokenService.getToken(id));
    }

    /**
     * 重置租户 Webhook Token：新 Token 落库，旧 Token 立即失效（DB + Redis 双清）
     */
    @PostMapping("/{id}/webhook-token/reset")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "重置WebhookToken")
    public R<String> resetWebhookToken(@PathVariable Long id) {
        String newToken = webhookTokenService.resetToken(id);
        return R.ok("Webhook Token 已重置，旧 Token 立即失效", newToken);
    }

    /**
     * 查看租户 PushPlus Token（明文，超管专用）
     */
    @GetMapping("/{id}/pushplus-token")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "查看PushPlusToken")
    public R<String> getPushplusToken(@PathVariable Long id) {
        return R.ok(pushplusTokenService.getToken(id));
    }

    /**
     * 配置 / 更新租户 PushPlus Token（body: {"token": "..."}，明文回显一次）
     */
    @PutMapping("/{id}/pushplus-token")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "配置PushPlusToken")
    public R<String> setPushplusToken(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String token = pushplusTokenService.setToken(id, body == null ? null : body.get("token"));
        return R.ok("PushPlus Token 已配置", token);
    }

    /**
     * 清空租户 PushPlus Token（停用 PushPlus 通道；重新配置即可恢复）
     */
    @DeleteMapping("/{id}/pushplus-token")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "租户管理", action = "清空PushPlusToken")
    public R<Void> clearPushplusToken(@PathVariable Long id) {
        pushplusTokenService.clearToken(id);
        return R.ok();
    }

    /**
     * Token 脱敏：保留前 6 后 4，中间掩码（32 位示例：a3f2b1******e9f0）
     */
    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "-";
        }
        if (token.length() <= 12) {
            return token.substring(0, 2) + "****";
        }
        return token.substring(0, 6) + "******" + token.substring(token.length() - 4);
    }
}
