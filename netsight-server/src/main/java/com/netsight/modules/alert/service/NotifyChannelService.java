package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.entity.NotifyChannel;
import com.netsight.modules.alert.mapper.NotifyChannelMapper;
import com.netsight.common.util.TokenCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 通知渠道实例管理服务
 * - CRUD（多租户隔离，密钥字段 AES 加密落库）
 * - 按实例 ID 解析 channelConfig（发送时注入 SendRequest）
 * - 每 5 分钟健康检查
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyChannelService {

    private final NotifyChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    private final TokenCrypto tokenCrypto;
    private final ChannelRegistry channelRegistry;

    /** 公共登录入口默认使用的微信服务号通道 ID；为空则按启用通道自动解析。 */
    @Value("${netsight.alert.channel.wechat.oauth-channel-id:}")
    private String configuredOAuthChannelId;

    /** 当前 Spring profile；生产环境禁止渠道配置开启 mockMode。 */
    @Value("${spring.profiles.active:}")
    private String activeProfile;

    /** 需要 AES 加密的配置字段（写入/读取时自动加解密） */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "token", "appSecret", "appKey", "accessKey", "accessSecret", "secret",
            "webhook", "password", "apiKey", "apiSecret", "corpSecret"
    );

    /** 渠道类型元数据（前端动态表单渲染依据） */
    private static final List<Map<String, Object>> CHANNEL_META = List.of(
            meta("aliyun_sms", "阿里云短信", List.of(
                    field("accessKeyId", "AccessKey ID", "text", true),
                    field("accessKeySecret", "AccessKey Secret", "password", true),
                    field("signName", "短信签名", "text", true),
                    field("templateCode", "模板 CODE", "text", true),
                    field("templateVarNames", "模板变量名", "text", false, "系统支持的变量：code（验证码）、deviceName（设备名）、deviceIp（设备IP）、deviceType（设备类型）、time（时间）、location（位置）、tenantName（租户名）、severity（级别）。多个用英文逗号分隔。留空默认 code。"),
                    field("endpoint", "Endpoint", "text", false)
            )),
            meta("tencent_sms", "腾讯云短信", List.of(
                    field("sdkAppId", "SDK AppID", "text", true),
                    field("secretId", "SecretId", "password", true),
                    field("secretKey", "SecretKey", "password", true),
                    field("signName", "短信签名", "text", true),
                    field("templateId", "模板 ID", "text", true),
                    field("region", "地域", "text", false)
            )),
            meta("dingtalk", "钉钉群机器人", List.of(
                    field("webhook", "Webhook 地址", "text", true),
                    field("secret", "加签 Secret", "password", false)
            )),
            meta("wechat_work", "企业微信群机器人", List.of(
                    field("webhook", "Webhook 地址", "text", true)
            )),
            meta("wechat_app", "企业微信应用消息", List.of(
                    field("corpId", "企业 ID", "text", true),
                    field("agentId", "应用 AgentId", "text", true),
                    field("corpSecret", "应用 Secret", "password", true),
                    field("toUser", "默认接收人(@all 可选)", "text", false)
            )),
            // 微信公众号（服务号）模板消息：我方主动推送，接收人为联系人的 wechat_openid
            meta("wechat", "微信公众号（模板消息）", List.of(
                    field("appId", "AppID", "text", true),
                    field("appSecret", "AppSecret", "password", true),
                    field("templateId", "默认模板 ID", "text", false),
                    field("url", "点击跳转地址", "text", false,
                            "默认 http://www.netsightplus.cn/m/orders"),
                    field("mockMode", "模拟发送", "switch", false,
                            "仅 dev/test 环境生效；生产环境不提供此开关且禁止开启"),
                    field("rateLimitPerMinute", "每人每分钟上限", "number", false,
                            "默认 4 条/分钟/用户"),
                    field("dedupTtlHours", "去重窗口（小时）", "number", false,
                            "默认 24 小时"),
                    field("timeoutMs", "接口超时（毫秒）", "number", false,
                            "默认 5000"),
                    field("retryIntervals", "重试间隔（毫秒）", "text", false,
                            "默认 1000,2000；最多 3 次尝试"),
                    field("oauthPublicBaseUrl", "OAuth 外部基址", "text", false,
                            "如 https://api.netsightplus.cn/prod-api")
            )),
            meta("feishu", "飞书群机器人", List.of(
                    field("webhook", "Webhook 地址", "text", true),
                    field("secret", "签名密钥", "password", false)
            )),
            meta("serverchan", "Server酱", List.of(
                    field("sendKey", "SENDKEY", "password", true)
            )),
            meta("pushplus", "PushPlus", List.of(
                    field("token", "PushPlus Token", "password", true),
                    field("topic", "群组编码（可选）", "text", false)
            )),
            meta("email", "邮件", List.of(
                    field("host", "SMTP 主机", "text", true),
                    field("port", "SMTP 端口", "number", true),
                    field("username", "发件账号", "text", true),
                    field("password", "授权码", "password", true),
                    field("from", "发件人", "text", true),
                    field("to", "默认收件人（逗号分隔）", "text", false),
                    field("ssl", "启用 SSL", "switch", false)
            )),
            meta("webhook", "通用 Webhook", List.of(
                    field("url", "请求 URL", "text", true),
                    field("method", "请求方法", "select:GET,POST,PUT", false),
                    field("secret", "签名密钥（可选）", "password", false),
                    field("bodyTemplate", "Body 模板（JSON）", "textarea", false)
            ))
    );

    public List<Map<String, Object>> listChannelMeta() {
        if (isMockProfile()) {
            return CHANNEL_META;
        }
        // 生产/预发布使用真实 Sender，不向界面暴露 mockMode 开关，避免误开模拟发送
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> meta : CHANNEL_META) {
            if (!"wechat".equals(meta.get("type"))) {
                result.add(meta);
                continue;
            }
            Map<String, Object> copy = new HashMap<>(meta);
            List<Map<String, String>> fields = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> original =
                    (List<Map<String, String>>) meta.get("fields");
            for (Map<String, String> f : original) {
                if (!"mockMode".equals(f.get("key"))) {
                    fields.add(f);
                }
            }
            copy.put("fields", fields);
            result.add(copy);
        }
        return result;
    }

    public PageResult<NotifyChannel> page(long pageNum, long pageSize, String channelType, String channelName) {
        LambdaQueryWrapper<NotifyChannel> w = new LambdaQueryWrapper<>();
        if (channelType != null && !channelType.isBlank()) w.eq(NotifyChannel::getChannelType, channelType);
        if (channelName != null && !channelName.isBlank()) w.like(NotifyChannel::getChannelName, channelName);
        w.orderByDesc(NotifyChannel::getId);
        Page<NotifyChannel> page = channelMapper.selectPage(new Page<>(pageNum, pageSize), w);
        // 列表不返回密钥明文
        page.getRecords().forEach(c -> c.setConfigJson(maskConfig(c.getConfigJson())));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public NotifyChannel getById(Long id) {
        NotifyChannel c = channelMapper.selectById(id);
        if (c == null) throw new ServiceException("渠道不存在");
        checkTenant(c.getTenantId());
        return c;
    }

    /** 详情：密钥字段返回掩码（编辑回显用，不回填真实密钥） */
    public NotifyChannel detail(Long id) {
        NotifyChannel c = getById(id);
        c.setConfigJson(maskConfig(c.getConfigJson()));
        return c;
    }

    public void add(NotifyChannel channel) {
        channel.setTenantId(currentTenantId());
        channel.setHealthStatus("unknown");
        assertMockDisabled(parseConfig(channel.getConfigJson()), channel.getChannelName());
        channel.setConfigJson(encryptConfig(channel.getConfigJson()));
        channelMapper.insert(channel);
    }

    public void update(NotifyChannel channel) {
        NotifyChannel exist = getById(channel.getId());
        // 编辑时前端提交的 configJson 中密钥字段若为掩码占位（以 **** 结尾或为空），保留旧值
        Map<String, Object> oldCfg = parseConfig(exist.getConfigJson());
        Map<String, Object> newCfg = parseConfig(channel.getConfigJson());
        for (String k : newCfg.keySet()) {
            Object v = newCfg.get(k);
            if (SECRET_FIELDS.contains(k) && (v == null || String.valueOf(v).isBlank() || String.valueOf(v).contains("****"))) {
                newCfg.put(k, oldCfg.get(k)); // 保留旧密钥
            }
        }
        assertMockDisabled(newCfg, channel.getChannelName());
        channel.setConfigJson(encryptConfig(toJson(newCfg)));
        channel.setTenantId(exist.getTenantId());
        channelMapper.updateById(channel);
    }

    public void delete(Long id) {
        NotifyChannel c = getById(id);
        channelMapper.deleteById(c.getId());
    }

    /**
     * 按实例 ID 列表解析渠道配置（发送时调用）。
     * 必须同时传入消息所属租户：跨租户或实例不存在直接拒绝，禁用实例跳过。
     * 返回有序：每条 = {channelType, channelName, config(Map 已解密), channelId, tenantId}
     */
    public List<Map<String, Object>> resolveChannels(List<Long> channelIds, Long tenantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (channelIds == null || channelIds.isEmpty()) return result;
        for (Long id : channelIds) {
            if (id == null) continue;
            NotifyChannel c = channelMapper.selectById(id);
            if (c == null) {
                throw new ServiceException("通道不存在或不属于当前租户，拒绝使用");
            }
            if (tenantId == null || !tenantId.equals(c.getTenantId())) {
                throw new ServiceException("通道不属于当前租户，拒绝使用");
            }
            if (c.getEnabled() != null && c.getEnabled() == 0) continue;
            Map<String, Object> cfg = parseConfig(c.getConfigJson());
            decryptConfig(cfg);
            if (isProd() && isMockModeOn(cfg)) {
                throw new ServiceException(
                        "生产环境禁止使用已开启模拟发送的渠道：" + c.getChannelName());
            }
            Map<String, Object> item = new HashMap<>();
            item.put("channelId", id);
            item.put("tenantId", c.getTenantId());
            item.put("channelType", c.getChannelType());
            item.put("channelName", c.getChannelName());
            item.put("config", cfg);
            result.add(item);
        }
        return result;
    }

    /**
     * 模板保存时校验绑定的通道实例：必须存在、启用且属于模板所属租户。
     */
    public void assertChannelUsable(Long channelId, Long tenantId) {
        if (channelId == null) {
            throw new ServiceException("绑定的通道不存在、未启用或不属于当前租户");
        }
        NotifyChannel c = channelMapper.selectById(channelId);
        if (c == null || c.getEnabled() == null || c.getEnabled() == 0
                || tenantId == null || !tenantId.equals(c.getTenantId())) {
            throw new ServiceException("绑定的通道不存在、未启用或不属于当前租户");
        }
    }

    /**
     * 解析 OAuth 使用的微信通道：
     * 显式 channelId 优先；否则使用配置的默认通道；否则按启用微信通道自动解析。
     * 同一 appId 多条通道取最小 ID，多个不同 appId 时必须显式指定。
     */
    public Map<String, Object> requireOAuthChannel(Long channelId, Long tenantId) {
        if (channelId != null) {
            NotifyChannel c = channelMapper.selectById(channelId);
            if (c == null) {
                throw new ServiceException("配置的微信 OAuth 通道 ID 非法");
            }
            return toOAuthChannel(c, tenantId);
        }
        if (configuredOAuthChannelId != null && !configuredOAuthChannelId.isBlank()) {
            Long configured;
            try {
                configured = Long.valueOf(configuredOAuthChannelId.trim());
            } catch (NumberFormatException e) {
                throw new ServiceException("配置的微信 OAuth 通道 ID 非法");
            }
            NotifyChannel c = channelMapper.selectById(configured);
            if (c == null) {
                throw new ServiceException("配置的微信 OAuth 通道 ID 非法");
            }
            return toOAuthChannel(c, tenantId);
        }
        List<NotifyChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getChannelType, "wechat")
                .eq(NotifyChannel::getEnabled, 1)
                .eq(tenantId != null, NotifyChannel::getTenantId, tenantId)
                .orderByAsc(NotifyChannel::getId));
        if (channels.isEmpty()) {
            throw new ServiceException("未配置微信 OAuth 通道");
        }
        if (distinctAppIds(channels).size() > 1) {
            throw new ServiceException("存在多个微信服务号，请显式指定 OAuth 通道 ID");
        }
        return toOAuthChannel(channels.get(0), tenantId);
    }

    /**
     * 按联系人所属租户解析启用的微信通道 ID（绑定二维码入口使用）。
     * 同一 appId 多条取最小 ID；多个不同 appId 时要求调用方显式指定 channelId。
     */
    public Long requireTenantWechatChannelId(Long tenantId) {
        if (tenantId == null) {
            throw new ServiceException("联系人缺少租户");
        }
        List<NotifyChannel> channels = channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getTenantId, tenantId)
                .eq(NotifyChannel::getChannelType, "wechat")
                .eq(NotifyChannel::getEnabled, 1)
                .orderByAsc(NotifyChannel::getId));
        if (channels.isEmpty()) {
            throw new ServiceException("该租户未配置启用的微信通道");
        }
        if (distinctAppIds(channels).size() > 1) {
            throw new ServiceException("该租户存在多个微信服务号，请指定 channelId");
        }
        return channels.get(0).getId();
    }

    private Set<String> distinctAppIds(List<NotifyChannel> channels) {
        Set<String> appIds = new HashSet<>();
        for (NotifyChannel c : channels) {
            String appId = appIdOf(c);
            if (appId != null && !appId.isBlank()) {
                appIds.add(appId);
            }
        }
        return appIds;
    }

    private String appIdOf(NotifyChannel c) {
        Map<String, Object> cfg = parseConfig(c.getConfigJson());
        decryptConfig(cfg);
        Object appId = cfg.get("appId");
        return appId == null ? null : String.valueOf(appId);
    }

    private Map<String, Object> toOAuthChannel(NotifyChannel c, Long tenantId) {
        if (!"wechat".equals(c.getChannelType())) {
            throw new ServiceException("微信公众号通道不可用");
        }
        if (c.getEnabled() == null || c.getEnabled() == 0) {
            throw new ServiceException("微信公众号通道未启用");
        }
        if (tenantId != null && !tenantId.equals(c.getTenantId())) {
            throw new ServiceException("微信公众号通道不属于当前租户");
        }
        Map<String, Object> cfg = parseConfig(c.getConfigJson());
        decryptConfig(cfg);
        String appId = cfg.get("appId") == null ? "" : String.valueOf(cfg.get("appId"));
        String appSecret = cfg.get("appSecret") == null ? "" : String.valueOf(cfg.get("appSecret"));
        if (appId.isBlank()) {
            throw new ServiceException("微信公众号通道未配置 appId");
        }
        if (appSecret.isBlank()) {
            throw new ServiceException("微信公众号通道凭据不完整");
        }
        Map<String, Object> item = new HashMap<>();
        item.put("channelId", c.getId());
        item.put("tenantId", c.getTenantId());
        item.put("channelType", c.getChannelType());
        item.put("channelName", c.getChannelName());
        item.put("config", cfg);
        return item;
    }

    /**
     * 系统级通道解析（登录验证码等无租户上下文场景）。
     * 按实例名 + 指定租户精确查，绕过租户拦截器，返回解密后的实例参数。
     */
    public Map<String, Object> resolveSystemChannel(String channelName, Long tenantId) {
        NotifyChannel c = channelMapper.selectSystemChannel(channelName, tenantId);
        if (c == null) return null;
        Map<String, Object> cfg = parseConfig(c.getConfigJson());
        decryptConfig(cfg);
        Map<String, Object> item = new HashMap<>();
        item.put("channelId", c.getId());
        item.put("channelType", c.getChannelType());
        item.put("channelName", c.getChannelName());
        item.put("config", cfg);
        return item;
    }

    /** 健康检查（每 5 分钟） */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void healthCheck() {
        List<NotifyChannel> all = channelMapper.selectList(
                new LambdaQueryWrapper<NotifyChannel>().eq(NotifyChannel::getEnabled, 1));
        for (NotifyChannel c : all) {
            try {
                NotificationChannelSender sender = channelRegistry.get(c.getChannelType());
                Map<String, Object> cfg = parseConfig(c.getConfigJson());
                decryptConfig(cfg);
                boolean ok = sender.healthCheck(cfg);
                c.setHealthStatus(ok ? "healthy" : "down");
            } catch (Exception e) {
                c.setHealthStatus("down");
                log.warn("渠道[{}]健康检查异常: {}", c.getChannelName(), e.getMessage());
            }
            c.setLastCheckTime(new Date());
            channelMapper.updateById(c);
        }
    }

    // ====== 工具方法 ======

    private boolean isProd() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        return Arrays.stream(activeProfile.split(","))
                .map(String::trim)
                .anyMatch("prod"::equals);
    }

    /** 只有 dev/test profile 会加载 Mock Sender，mockMode 开关才有效。 */
    private boolean isMockProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return false;
        }
        return Arrays.stream(activeProfile.split(","))
                .map(String::trim)
                .anyMatch(p -> "dev".equals(p) || "test".equals(p));
    }

    private void assertMockDisabled(Map<String, Object> cfg, String channelName) {
        if (isProd() && isMockModeOn(cfg)) {
            log.warn("生产环境拒绝保存模拟发送渠道: {}", channelName);
            throw new ServiceException("生产环境禁止开启模拟发送（mockMode=true）");
        }
    }

    private static boolean isMockModeOn(Map<String, Object> cfg) {
        if (cfg == null || cfg.get("mockMode") == null) {
            return false;
        }
        Object v = cfg.get("mockMode");
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private Long currentTenantId() {
        try { return SecurityUtils.getLoginUser().getTenantId(); }
        catch (Exception e) { return 0L; }
    }

    private void checkTenant(Long tenantId) {
        if (SecurityUtils.isSuperAdmin()) return;
        Long cur = currentTenantId();
        if (tenantId == null || !tenantId.equals(cur)) throw new ServiceException("无权访问该渠道");
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return new HashMap<>(); }
    }

    private String toJson(Map<String, Object> m) {
        try { return objectMapper.writeValueAsString(m); }
        catch (Exception e) { return "{}"; }
    }

    /** 写入前：密钥字段 AES 加密 */
    private String encryptConfig(String json) {
        Map<String, Object> cfg = parseConfig(json);
        for (String k : SECRET_FIELDS) {
            Object v = cfg.get(k);
            if (v != null && !String.valueOf(v).isBlank() && !String.valueOf(v).startsWith("enc:")) {
                cfg.put(k, tokenCrypto.encrypt(String.valueOf(v)));
            }
        }
        return toJson(cfg);
    }

    /** 读取后：密钥字段 AES 解密 */
    private void decryptConfig(Map<String, Object> cfg) {
        for (String k : SECRET_FIELDS) {
            Object v = cfg.get(k);
            if (v != null && String.valueOf(v).startsWith("enc:")) {
                cfg.put(k, tokenCrypto.decrypt(String.valueOf(v)));
            }
        }
    }

    /** 列表/详情：密钥字段掩码 */
    private String maskConfig(String json) {
        Map<String, Object> cfg = parseConfig(json);
        for (String k : SECRET_FIELDS) {
            Object v = cfg.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                String s = String.valueOf(v);
                cfg.put(k, s.length() <= 4 ? "****" : s.substring(0, 4) + "****");
            }
        }
        return toJson(cfg);
    }

    private static Map<String, Object> meta(String type, String name, List<Map<String, String>> fields) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("name", name);
        m.put("fields", fields);
        return m;
    }

    private static Map<String, String> field(String key, String label, String type, boolean required) {
        Map<String, String> f = new HashMap<>();
        f.put("key", key); f.put("label", label); f.put("type", type);
        f.put("required", String.valueOf(required));
        return f;
    }

    /** 带提示文案的字段（前端在 label 旁渲染小问号 tooltip） */
    private static Map<String, String> field(String key, String label, String type, boolean required, String hint) {
        Map<String, String> f = field(key, label, type, required);
        f.put("hint", hint);
        return f;
    }
}
