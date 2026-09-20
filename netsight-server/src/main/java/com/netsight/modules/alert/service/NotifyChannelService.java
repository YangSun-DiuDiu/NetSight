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
        return CHANNEL_META;
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
     * 返回有序：每条 = {channelType, channelName, config(Map 已解密), channelId}
     */
    public List<Map<String, Object>> resolveChannels(List<Long> channelIds) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (channelIds == null || channelIds.isEmpty()) return result;
        for (Long id : channelIds) {
            NotifyChannel c = channelMapper.selectById(id);
            if (c == null || (c.getEnabled() != null && c.getEnabled() == 0)) continue;
            Map<String, Object> cfg = parseConfig(c.getConfigJson());
            decryptConfig(cfg);
            Map<String, Object> item = new HashMap<>();
            item.put("channelId", id);
            item.put("channelType", c.getChannelType());
            item.put("channelName", c.getChannelName());
            item.put("config", cfg);
            result.add(item);
        }
        return result;
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
}
