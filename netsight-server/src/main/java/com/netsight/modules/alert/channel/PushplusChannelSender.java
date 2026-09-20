package com.netsight.modules.alert.channel;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.netsight.common.util.TokenCrypto;
import com.netsight.modules.system.entity.EdgeGateway;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.EdgeGatewayMapper;
import com.netsight.modules.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PushPlus 通道适配器（channelType = pushplus）
 *
 * 设计要点（方案 5.8 通道 SPI + 租户级 Token 模型）：
 * 1. <b>租户级 Token</b>：PushPlus 个人 token 由租户在云平台租户管理中配置
 *    （sys_tenant.pushplus_token），发送时按事件租户取用——Token 即租户身份，
 *    与 Webhook Token 同一信任模型；未配置 Token 的租户发送直接返回失败，不误发他人 Token。
 * 2. 发送协议：POST https://www.pushplus.plus/send，JSON {token, title, content, template, topic}；
 *    返回体 code==200 视为成功；异常统一包装为 SendResult，不向外抛。
 * 3. 开发阶段 mock-mode=true：仅打印日志模拟发送（无真实 Token 也能联调）；
 *    生产 mock-mode=false + 租户配置真实 Token 即生效。
 *
 * 新增通道规范对照：接口方法齐全 / channelType 唯一 / 密钥不硬编码（DB+配置读取）/
 * 异常包装 / 幂等由上层 bizId 去重兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushplusChannelSender implements NotificationChannelSender {

    /** PushPlus 发送 API 地址（生产可经环境变量 PUSHPLUS_API_URL 覆盖） */
    @Value("${netsight.alert.channel.pushplus.api-url:https://www.pushplus.plus/send}")
    private String apiUrl;

    /**
     * 开发模拟开关（默认 false：生产安全默认，未显式开启即真实发送；
     * 开发联调需在 application.yml 显式 mock-mode: true，防止误配置导致生产静默不发送）
     */
    @Value("${netsight.alert.channel.pushplus.mock-mode:false}")
    private boolean mockMode;

    /** HTTP 超时（ms） */
    @Value("${netsight.alert.channel.pushplus.timeout-ms:5000}")
    private int timeoutMs;

    /** 通知标题前缀（内容模板已带前缀时可为空） */
    @Value("${netsight.alert.channel.pushplus.title-prefix:【运维告警】}")
    private String titlePrefix;

    private final SysTenantMapper sysTenantMapper;
    private final EdgeGatewayMapper edgeGatewayMapper;
    private final TokenCrypto tokenCrypto;

    @jakarta.annotation.PostConstruct
    public void init() {
        if (mockMode) {
            log.warn("【PushPlus】mock-mode=true 已开启：所有推送仅打印日志、不真实发送。"
                    + "生产环境请确认 application.yml / 环境变量已显式设置 netsight.alert.channel.pushplus.mock-mode=false");
        }
    }

    @Override
    public String getChannelType() {
        return "pushplus";
    }

    @Override
    public String getChannelName() {
        return "PushPlus 推送";
    }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        try {
            // 1. 按事件解析 PushPlus Token：
            //    优先渠道实例配置（notify_channel.config_json.token），未配置回退租户级/网关级
            String token = null;
            if (request.getChannelConfig() != null && request.getChannelConfig().get("token") != null) {
                token = String.valueOf(request.getChannelConfig().get("token"));
            }
            if (!StringUtils.hasText(token)) {
                token = resolveTenantToken(request.getTenantId(), request.getGatewayCode());
            }
            if (!StringUtils.hasText(token)) {
                String msg = "租户[" + request.getTenantId() + "]未配置 PushPlus Token，请先在租户管理或边缘网关管理中配置";
                log.warn("【PushPlus】{}", msg);
                return SendResult.builder()
                        .success(false)
                        .errorMsg(msg)
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }

            // 2. 开发模拟：仅打印日志
            if (mockMode) {
                log.info("【PushPlus MOCK】租户={} 接收人={} 标题={} 内容={}",
                        request.getTenantId(), request.getReceiverList(),
                        buildTitle(request), request.getContent());
                return SendResult.builder()
                        .success(true)
                        .thirdPartyMsgId("MOCK-PP-" + UUID.randomUUID().toString().substring(0, 8))
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }

            // 3. 生产：调用 PushPlus 官方 API
            Map<String, Object> payload = new HashMap<>();
            payload.put("token", token);
            payload.put("title", buildTitle(request));
            payload.put("content", request.getContent() == null ? "" : request.getContent());
            payload.put("template", "html");
            // 群组推送（可选）：仅当调用方显式指定 extra.topic 时使用。
            // 注意：PushPlus 的 topic 是"群组名称"，不是接收人标识——接收人列表不得映射为 topic，
            // 否则平台返回 code=999"群组信息不存在"（服务端验证错误）。个人 token 推送无需 topic。
            Object extraTopic = request.getExtra() == null ? null : request.getExtra().get("topic");
            if (extraTopic != null && StringUtils.hasText(String.valueOf(extraTopic))) {
                payload.put("topic", String.valueOf(extraTopic));
            }

            String resp = HttpUtil.post(apiUrl, JSONUtil.toJsonStr(payload), timeoutMs);
            // 【优化】响应体可能较长，日志只保留前 500 字符，避免刷屏/泄露完整响应细节
            String logResp = (resp != null && resp.length() > 500) ? resp.substring(0, 500) + "...(truncated)" : resp;
            log.info("【PushPlus】租户={} 响应={}", request.getTenantId(), logResp);
            if (!StringUtils.hasText(resp)) {
                return SendResult.builder()
                        .success(false)
                        .errorMsg("PushPlus 返回空响应")
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }
            JSONObject json = JSONUtil.parseObj(resp);
            int code = json.getInt("code", -1);
            if (code != 200) {
                return SendResult.builder()
                        .success(false)
                        .errorMsg("PushPlus 返回异常: " + json.getStr("msg", resp))
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }
            return SendResult.builder()
                    .success(true)
                    .thirdPartyMsgId(String.valueOf(json.get("data")))
                    .costTime(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("PushPlus 推送失败: {}", e.getMessage(), e);
            return SendResult.builder()
                    .success(false)
                    .errorMsg(e.getMessage())
                    .costTime(System.currentTimeMillis() - start)
                    .build();
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) {
        // mock 模式恒可用；生产可扩展为探测 PushPlus 网关连通性
        return true;
    }

    /**
     * 按事件解析 PushPlus Token（优先级：网关级 → 租户级；跨租户防护：网关必须归属事件租户）
     * sys_tenant / edge_gateway 均为公共表，不受租户过滤，故需显式按 tenantId 校验归属。
     * 注：库内为密文（enc: 前缀），返回前解密为明文供发送。
     */
    private String resolveTenantToken(Long tenantId, String gatewayCode) {
        if (tenantId == null) {
            return null;
        }
        // 1. 网关级优先（事件携带 gateway_code 时；网关须归属该租户且已启用）
        if (StringUtils.hasText(gatewayCode)) {
            EdgeGateway gateway = edgeGatewayMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EdgeGateway>()
                            .eq(EdgeGateway::getGatewayCode, gatewayCode)
                            .eq(EdgeGateway::getTenantId, tenantId)
                            .last("LIMIT 1"));
            if (gateway != null && gateway.getStatus() != null && gateway.getStatus() == 1
                    && StringUtils.hasText(gateway.getPushplusToken())) {
                return tokenCrypto.decrypt(gateway.getPushplusToken());
            }
        }
        // 2. 租户级兜底
        SysTenant tenant = sysTenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            return null;
        }
        return tokenCrypto.decrypt(tenant.getPushplusToken());
    }

    /** 组装标题（内容模板已带前缀时 title-prefix 置空即可） */
    private String buildTitle(SendRequest request) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(titlePrefix)) {
            sb.append(titlePrefix);
        }
        Object title = request.getExtra() == null ? null : request.getExtra().get("title");
        sb.append(title == null ? "NetSight 设备告警" : title);
        return sb.toString();
    }
}
