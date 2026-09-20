package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.util.TokenCrypto;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 租户级 PushPlus Token 服务（方案 5.8 通道 SPI + 租户级密钥模型）
 *
 * 与 WebhookTokenService 同一信任模型：<b>Token 即租户身份</b>——
 * PushPlus 个人 token 由租户在云平台租户管理中配置（不随边缘网关下发，
 * 通知由云端发出，配置收口云端单一维护点），发送时按事件租户取用。
 *
 * 职责：
 * 1. 查看 / 配置 / 清空租户 PushPlus Token（租户管理域，超管专用）
 * 2. PushPlus 通道发送时按 tenantId 读取（读取逻辑在 PushplusChannelSender）
 *
 * 与 Webhook Token 的区别：Webhook Token 由系统生成（UUID），
 * PushPlus Token 是用户在 PushPlus 平台申请的个人/群组 token，系统只负责保管与绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushplusTokenService {

    private final SysTenantMapper sysTenantMapper;
    private final TokenCrypto tokenCrypto;

    /**
     * 查看租户 PushPlus Token（明文，超管专用；库内密文解密返回）
     */
    public String getToken(Long tenantId) {
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        return tokenCrypto.decrypt(tenant.getPushplusToken());
    }

    /**
     * 配置 / 更新租户 PushPlus Token（密文落库）
     * 校验：Token 非空；长度不超过 64（PushPlus token 通常 32 位左右）
     *
     * @return 配置后的 Token（明文回显一次，便于前端确认）
     */
    public String setToken(Long tenantId, String token) {
        if (!StringUtils.hasText(token)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "PushPlus Token 不能为空");
        }
        String trimmed = token.trim();
        if (trimmed.length() > 64) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "PushPlus Token 长度不能超过 64 位");
        }
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        SysTenant update = new SysTenant();
        update.setId(tenantId);
        update.setPushplusToken(tokenCrypto.encrypt(trimmed));
        update.setPushplusTokenTime(LocalDateTime.now());
        sysTenantMapper.updateById(update);
        log.info("租户[{}] PushPlus Token 已配置/更新", tenantId);
        return trimmed;
    }

    /**
     * 清空租户 PushPlus Token（停用 PushPlus 通知通道；后续如需再次启用重新配置即可）
     * 注：updateById 默认忽略 null 字段，清空必须用 UpdateWrapper 显式 SET NULL
     */
    public void clearToken(Long tenantId) {
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new ServiceException(ResultCode.TENANT_NOT_FOUND);
        }
        sysTenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, tenantId)
                .set(SysTenant::getPushplusToken, null)
                .set(SysTenant::getPushplusTokenTime, LocalDateTime.now()));
        log.info("租户[{}] PushPlus Token 已清空", tenantId);
    }
}
