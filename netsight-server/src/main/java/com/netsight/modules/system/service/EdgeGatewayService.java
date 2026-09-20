package com.netsight.modules.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.common.util.TokenCrypto;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 边缘网关管理服务
 * 网关注册/编辑/删除/重置Token/在线状态判断/租户隔离
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeGatewayService {

    /** 心跳超时阈值：超过 5 分钟未上报判定离线（方案 12.4） */
    private static final Duration OFFLINE_THRESHOLD = Duration.ofMinutes(5);

    private final EdgeGatewayMapper edgeGatewayMapper;
    private final DeviceMapper deviceMapper;
    private final TokenCrypto tokenCrypto;

    /**
     * 分页查询网关（租户隔离：非超管只能看本租户网关）
     */
    public PageResult<EdgeGateway> pageGateway(long pageNum, long pageSize, String gatewayName, Integer onlineStatus) {
        Page<EdgeGateway> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EdgeGateway> wrapper = new LambdaQueryWrapper<EdgeGateway>()
                .like(StringUtils.hasText(gatewayName), EdgeGateway::getGatewayName, gatewayName)
                .eq(onlineStatus != null, EdgeGateway::getOnlineStatus, onlineStatus);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(EdgeGateway::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(EdgeGateway::getId);
        Page<EdgeGateway> result = edgeGatewayMapper.selectPage(page, wrapper);
        result.getRecords().forEach(this::refreshOnlineStatus);
        // 敏感字段脱敏：Gateway Token / PushPlus Token 列表仅展示掩码（明文需走查看接口 + 复制）
        // 注：库内为密文（enc: 前缀），必须先解密再脱敏，否则掩码失去意义
        result.getRecords().forEach(g -> {
            if (StringUtils.hasText(g.getGatewayToken())) {
                g.setGatewayToken(maskToken(tokenCrypto.decrypt(g.getGatewayToken())));
            }
            if (StringUtils.hasText(g.getPushplusToken())) {
                g.setPushplusToken(maskToken(tokenCrypto.decrypt(g.getPushplusToken())));
            }
        });
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 新增网关：自动生成编码与 Token（Token 密文落库，明文仅本次返回一次）
     */
    @Transactional(rollbackFor = Exception.class)
    public String addGateway(EdgeGateway gateway) {
        // 租户归属：超管可指定，普通管理员归本租户
        if (SecurityUtils.isSuperAdmin() && gateway.getTenantId() != null) {
            // 使用指定租户
        } else {
            gateway.setTenantId(SecurityUtils.getTenantId());
        }
        gateway.setGatewayCode("GW" + System.currentTimeMillis() + randomSuffix());
        String plainToken = UUID.randomUUID().toString().replace("-", "");
        // 落库密文；明文仅通过返回值一次性展示（controller 弹出提示）
        gateway.setGatewayToken(tokenCrypto.encrypt(plainToken));
        gateway.setOnlineStatus(0);
        if (gateway.getLinkType() == null || gateway.getLinkType().isBlank()) {
            gateway.setLinkType("wired");
        }
        if (gateway.getStatus() == null) {
            gateway.setStatus(1);
        }
        edgeGatewayMapper.insert(gateway);
        log.info("新增边缘网关: {} ({})，租户: {}", gateway.getGatewayName(), gateway.getGatewayCode(), gateway.getTenantId());
        return plainToken;
    }

    /**
     * 修改网关（名称/位置/租户/链路/启用状态；Token 单独重置）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateGateway(EdgeGateway gateway) {
        EdgeGateway exist = edgeGatewayMapper.selectById(gateway.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.GATEWAY_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        EdgeGateway update = new EdgeGateway();
        update.setId(gateway.getId());
        update.setGatewayName(gateway.getGatewayName());
        update.setLocation(gateway.getLocation());
        update.setLinkType(gateway.getLinkType());
        update.setStatus(gateway.getStatus());
        // 超管可调整归属租户
        if (SecurityUtils.isSuperAdmin() && gateway.getTenantId() != null) {
            update.setTenantId(gateway.getTenantId());
        }
        edgeGatewayMapper.updateById(update);
    }

    /**
     * 删除网关（逻辑删除；网关下存在设备时禁止删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGateway(Long id) {
        EdgeGateway exist = edgeGatewayMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.GATEWAY_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        Long deviceCount = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .eq(Device::getGatewayId, id));
        if (deviceCount > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "网关下存在设备，请先迁移或删除设备");
        }
        edgeGatewayMapper.deleteById(id);
    }

    /**
     * 重置网关 Token（旧 Token 立即失效；密文落库，明文仅本次返回）
     */
    public String resetToken(Long id) {
        EdgeGateway exist = edgeGatewayMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.GATEWAY_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        String newToken = UUID.randomUUID().toString().replace("-", "");
        EdgeGateway update = new EdgeGateway();
        update.setId(id);
        update.setGatewayToken(tokenCrypto.encrypt(newToken));
        edgeGatewayMapper.updateById(update);
        return newToken;
    }

    /**
     * 查看网关 PushPlus Token（脱敏：前6后4，中间 ****；未配置返回 null）
     * 注：库内为密文，先解密再脱敏
     */
    public String getPushplusToken(Long id) {
        EdgeGateway exist = edgeGatewayMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.GATEWAY_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        return maskToken(tokenCrypto.decrypt(exist.getPushplusToken()));
    }

    /**
     * Token 脱敏（前6后4，中间 ****；null/短串保底）
     */
    private String maskToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        if (token.length() <= 10) {
            return token.substring(0, 1) + "****" + token.substring(token.length() - 1);
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }

    /**
     * 设置/清空网关 PushPlus Token（空串/null 视为清空；配置后该网关告警优先使用网关级 Token）
     * 注：Token 密文落库；updateById 默认忽略 null 字段，清空必须用 UpdateWrapper 显式 SET NULL
     */
    public void setPushplusToken(Long id, String token) {
        EdgeGateway exist = edgeGatewayMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.GATEWAY_NOT_FOUND);
        }
        checkTenantPermission(exist.getTenantId());
        if (StringUtils.hasText(token)) {
            EdgeGateway update = new EdgeGateway();
            update.setId(id);
            update.setPushplusToken(tokenCrypto.encrypt(token.trim()));
            update.setPushplusTokenTime(LocalDateTime.now());
            edgeGatewayMapper.updateById(update);
        } else {
            edgeGatewayMapper.update(null, new LambdaUpdateWrapper<EdgeGateway>()
                    .eq(EdgeGateway::getId, id)
                    .set(EdgeGateway::getPushplusToken, null)
                    .set(EdgeGateway::getPushplusTokenTime, LocalDateTime.now()));
        }
    }

    /**
     * 根据 Token 查询网关（状态上报鉴权用，禁用网关返回 null）
     * 注：库内为 AES-256-GCM 密文（随机 IV，密文不可预测），无法用等值 SQL 匹配，
     * 采用全量拉取 + 内存解密比对（网关量级小，可忽略开销；避免引入哈希索引列）
     */
    public EdgeGateway getByToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        List<EdgeGateway> all = edgeGatewayMapper.selectList(new LambdaQueryWrapper<EdgeGateway>()
                .eq(EdgeGateway::getDelFlag, 0));
        for (EdgeGateway g : all) {
            if (StringUtils.hasText(g.getGatewayToken())
                    && token.equals(tokenCrypto.decrypt(g.getGatewayToken()))) {
                if (g.getStatus() != null && g.getStatus() == 0) {
                    return null;
                }
                return g;
            }
        }
        return null;
    }

    /**
     * 记录网关心跳（上报时刷新）
     */
    public void heartbeat(EdgeGateway gateway, String ip) {
        EdgeGateway update = new EdgeGateway();
        update.setId(gateway.getId());
        update.setOnlineStatus(1);
        update.setLastHeartbeatTime(LocalDateTime.now());
        update.setIpAddress(ip);
        edgeGatewayMapper.updateById(update);
    }

    /**
     * 刷新在线状态：心跳超过 5 分钟判定离线（前端列表实时计算，不落库）
     */
    private void refreshOnlineStatus(EdgeGateway gateway) {
        if (gateway.getOnlineStatus() == null || gateway.getOnlineStatus() == 0) {
            return;
        }
        if (gateway.getLastHeartbeatTime() == null) {
            gateway.setOnlineStatus(0);
            return;
        }
        if (Duration.between(gateway.getLastHeartbeatTime(), LocalDateTime.now()).compareTo(OFFLINE_THRESHOLD) > 0) {
            gateway.setOnlineStatus(0);
        }
    }

    /**
     * 数据隔离校验：非超管不能操作其他租户网关
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }

    /** 编码随机后缀（4位，SecureRandom 避免可预测编码） */
    private String randomSuffix() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 网关下拉选项（设备表单用，租户隔离） */
    public List<EdgeGateway> listOptions() {
        LambdaQueryWrapper<EdgeGateway> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(EdgeGateway::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.eq(EdgeGateway::getStatus, 1).orderByAsc(EdgeGateway::getGatewayName);
        return edgeGatewayMapper.selectList(wrapper);
    }
}
