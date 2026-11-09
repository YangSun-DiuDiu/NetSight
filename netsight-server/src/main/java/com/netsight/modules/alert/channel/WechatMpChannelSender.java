package com.netsight.modules.alert.channel;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信公众号（服务号）模板消息通道适配器（channelType = wechat）
 *
 * <p>定位：我方主动调用微信模板消息接口推送设备离线告警（不是接收公众号上行消息）。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>配置全部来自渠道实例</b>（notify_channel.config_json，已由通道中心解密后注入）：
 *       appId / appSecret / templateId / url / mockMode，不在代码里硬编码密钥。</li>
 *   <li><b>access_token 独立缓存</b>：使用 cgi-bin/stable_token（force_refresh=false）获取，
 *       与既有 JS-SDK 签名服务（127.0.0.1:9091）的普通 token 属于两套独立缓存，互不影响。</li>
 *   <li><b>模板字段固定</b>：thing2 告警对象 / phrase9 事件状态 / character_string11 设备编号 /
 *       thing17 故障位置 / phrase20 告警等级（key 必须逐字一致，不能简写成 thing/phrase）。</li>
 *   <li><b>跳转</b>：点击模板卡片跳移动端 H5 待接单页（默认 /m/orders，可在渠道实例 url 中覆盖）。</li>
 *   <li>异常统一包装成 SendResult，不向外抛运行时异常；同一 bizId 的幂等由通道中心 Redis 去重兜底。</li>
 * </ol>
 */
@Slf4j
@Component
public class WechatMpChannelSender implements NotificationChannelSender {

    /** 通道类型标识：微信服务号模板消息（与 wechat_work / wechat_app 区分） */
    public static final String CHANNEL_TYPE = "wechat";

    private static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String TEMPLATE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/template/send";

    /** 默认跳转：移动端 H5 工单列表（默认停在「待接单」Tab） */
    private static final String DEFAULT_JUMP_URL = "http://www.netsightplus.cn/m/orders";

    /** token 提前失效的保守余量（秒），避免临界点被微信判失效 */
    private static final long TOKEN_SAFE_SECONDS = 300L;

    /** token 缓存：appId -> 缓存项（进程内静态，按渠道实例隔离） */
    private static final Map<String, TokenCache> TOKEN_CACHE = new ConcurrentHashMap<>();

    /** 开发模拟开关（默认 false：生产安全默认，未显式开启即真实发送） */
    @Value("${netsight.alert.channel.wechat.mock-mode:false}")
    private boolean mockMode;

    /** HTTP 超时（ms） */
    @Value("${netsight.alert.channel.wechat.timeout-ms:5000}")
    private int timeoutMs;

    /** 默认跳转地址（可在渠道实例 config.url 中覆盖） */
    @Value("${netsight.alert.channel.wechat.jump-url:" + DEFAULT_JUMP_URL + "}")
    private String defaultJumpUrl;

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public String getChannelName() {
        return "微信公众号（模板消息）";
    }

    @Override
    public SendResult send(SendRequest request) {
        long start = System.currentTimeMillis();
        Map<String, Object> cfg = request.getChannelConfig() == null
                ? new HashMap<>() : request.getChannelConfig();
        try {
            String appId = text(cfg, "appId");
            String appSecret = text(cfg, "appSecret");
            String templateId = text(cfg, "templateId");
            if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)
                    || !StringUtils.hasText(templateId)) {
                return fail("微信公众号通道实例缺少 appId / appSecret / templateId 配置", start);
            }

            List<String> receivers = request.getReceiverList() == null
                    ? new ArrayList<>() : request.getReceiverList();
            if (receivers.isEmpty()) {
                // 43004 的本地前置：联系人未绑定 openid 时不会走到微信接口
                return fail("无有效微信接收人（联系人的 wechat_openid 为空）", start);
            }

            boolean mock = parseBool(cfg.get("mockMode"), mockMode);
            if (mock) {
                log.info("【微信公众号 MOCK】appId={} 接收人数={} 模板={} 跳转={}",
                        appId, receivers.size(), templateId, resolveJumpUrl(request, cfg));
                return SendResult.builder()
                        .success(true)
                        .thirdPartyMsgId("MOCK-WX-" + System.currentTimeMillis())
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }

            String jumpUrl = resolveJumpUrl(request, cfg);
            Map<String, Object> data = buildData(request);
            String token = getAccessToken(appId, appSecret, false).token;

            int successCount = 0;
            List<String> errors = new ArrayList<>();
            String lastMsgId = null;
            for (String openid : receivers) {
                if (!StringUtils.hasText(openid)) {
                    continue;
                }
                JSONObject payload = new JSONObject();
                payload.set("touser", openid);
                payload.set("template_id", templateId);
                if (StringUtils.hasText(jumpUrl)) {
                    payload.set("url", jumpUrl);
                }
                payload.set("data", data);

                String resp = HttpUtil.post(TEMPLATE_SEND_URL + "?access_token=" + token,
                        JSONUtil.toJsonStr(payload), timeoutMs);
                JSONObject json = parse(resp);
                int errcode = json == null ? -1 : json.getInt("errcode", -1);

                // token 失效：清缓存强制重取后重试一次（40001/40014/42001 属同类）
                if (errcode == 40001 || errcode == 40014 || errcode == 42001) {
                    log.warn("【微信公众号】access_token 失效(errcode={})，强制刷新后重试 openid={}",
                            errcode, mask(openid));
                    token = getAccessToken(appId, appSecret, true).token;
                    resp = HttpUtil.post(TEMPLATE_SEND_URL + "?access_token=" + token,
                            JSONUtil.toJsonStr(payload), timeoutMs);
                    json = parse(resp);
                    errcode = json == null ? -1 : json.getInt("errcode", -1);
                }

                if (errcode == 0) {
                    successCount++;
                    lastMsgId = json == null ? null : json.getStr("msgid");
                } else {
                    String errmsg = json == null ? "返回空响应" : json.getStr("errmsg", "");
                    errors.add(mask(openid) + ":" + errcode + "(" + errorHint(errcode) + ")");
                    log.warn("【微信公众号】发送失败 openid={} errcode={} errmsg={}",
                            mask(openid), errcode, errmsg);
                }
            }

            if (successCount == 0) {
                return SendResult.builder()
                        .success(false)
                        .errorMsg("微信模板消息全部发送失败: " + String.join("; ", errors))
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }
            if (!errors.isEmpty()) {
                log.warn("【微信公众号】部分接收人发送失败: {}", String.join("; ", errors));
            }
            return SendResult.builder()
                    .success(true)
                    .thirdPartyMsgId(lastMsgId)
                    .costTime(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("【微信公众号】发送异常: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(java.util.Map<String, Object> config) {
        // 仅做配置完整性校验，不主动调用微信接口（避免定时巡检消耗 token 接口频次）
        if (config == null) {
            return false;
        }
        return StringUtils.hasText(text(config, "appId"))
                && StringUtils.hasText(text(config, "appSecret"))
                && StringUtils.hasText(text(config, "templateId"));
    }

    // ==================== 内部实现 ====================

    /**
     * 组装模板数据（5 个字段，key 必须与服务号模板逐字一致）
     *
     * <ul>
     *   <li>thing2 告警对象 ← device_name</li>
     *   <li>phrase9 事件状态 ← event_type（离线/链路异常/已恢复）</li>
     *   <li>character_string11 设备编号 ← device_code（labels 有则用），回落 device_ip</li>
     *   <li>thing17 故障位置 ← location / device_location</li>
     *   <li>phrase20 告警等级 ← severity（严重/重要/一般/已恢复）</li>
     * </ul>
     * 微信对 thing ≤20 字符、phrase ≤5 汉字、character_string ≤32 字符有硬限制，超长会报 47003，此处防御性截断。
     */
    private Map<String, Object> buildData(SendRequest request) {
        Map<String, Object> vars = request.getContentVars() == null
                ? new HashMap<>() : request.getContentVars();

        String deviceName = firstText(vars, "device_name", "deviceName");
        String deviceCode = firstText(vars, "device_code", "deviceCode");
        String deviceIp = firstText(vars, "device_ip", "deviceIp");
        String location = firstText(vars, "location", "device_location", "deviceLocation");
        String eventType = firstText(vars, "event_type", "eventType");
        String severity = firstText(vars, "severity");

        String deviceNo = StringUtils.hasText(deviceCode) ? deviceCode : deviceIp;

        Map<String, Object> data = new HashMap<>(8);
        data.put("thing2", value(cut(StringUtils.hasText(deviceName) ? deviceName : "未知设备", 20)));
        data.put("phrase9", value(cut(eventStateText(eventType), 5)));
        data.put("character_string11", value(cut(StringUtils.hasText(deviceNo) ? deviceNo : "-", 32)));
        data.put("thing17", value(cut(StringUtils.hasText(location) ? location : "未登记位置", 20)));
        data.put("phrase20", value(cut(severityText(severity), 5)));
        return data;
    }

    /** 事件状态文案（phrase 限 5 个汉字） */
    private String eventStateText(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return "告警";
        }
        return switch (eventType) {
            case "device_offline" -> "离线";
            case "device_line_abnormal" -> "链路异常";
            case "device_recovered" -> "已恢复";
            case "device_cpu_high" -> "CPU过高";
            case "device_mem_high" -> "内存过高";
            default -> "告警";
        };
    }

    /** 告警等级文案（phrase 限 5 个汉字） */
    private String severityText(String severity) {
        if (!StringUtils.hasText(severity)) {
            return "一般";
        }
        return switch (severity.toLowerCase()) {
            case "critical", "fatal", "emergency" -> "严重";
            case "warning", "warn", "major" -> "重要";
            case "resolved", "ok", "recovered" -> "已恢复";
            case "info", "notice", "minor" -> "一般";
            default -> "告警";
        };
    }

    /** 跳转地址：extra.wechatUrl 优先 → 渠道实例 url → 全局默认 */
    private String resolveJumpUrl(SendRequest request, Map<String, Object> cfg) {
        Object extraUrl = request.getExtra() == null ? null : request.getExtra().get("wechatUrl");
        if (extraUrl != null && StringUtils.hasText(String.valueOf(extraUrl))) {
            return String.valueOf(extraUrl);
        }
        String cfgUrl = text(cfg, "url");
        if (StringUtils.hasText(cfgUrl)) {
            return cfgUrl;
        }
        return StringUtils.hasText(defaultJumpUrl) ? defaultJumpUrl : DEFAULT_JUMP_URL;
    }

    /**
     * 获取 access_token（进程内缓存，提前 5 分钟失效）
     *
     * @param forceRefresh true=忽略缓存强制重取（token 失效错误码触发）
     */
    private TokenCache getAccessToken(String appId, String appSecret, boolean forceRefresh) {
        TokenCache cached = TOKEN_CACHE.get(appId);
        long now = System.currentTimeMillis() / 1000;
        if (!forceRefresh && cached != null && cached.expireAt > now) {
            return cached;
        }
        synchronized (TOKEN_CACHE) {
            cached = TOKEN_CACHE.get(appId);
            if (!forceRefresh && cached != null && cached.expireAt > now) {
                return cached;
            }
            JSONObject body = new JSONObject();
            body.set("grant_type", "client_credential");
            body.set("appid", appId);
            body.set("secret", appSecret);
            body.set("force_refresh", forceRefresh);
            String resp = HttpUtil.post(STABLE_TOKEN_URL, JSONUtil.toJsonStr(body), timeoutMs);
            JSONObject json = parse(resp);
            String token = json == null ? null : json.getStr("access_token");
            if (!StringUtils.hasText(token)) {
                int errcode = json == null ? -1 : json.getInt("errcode", -1);
                String errmsg = json == null ? "空响应" : json.getStr("errmsg", "");
                throw new IllegalStateException("获取 access_token 失败: " + errcode + " " + errmsg
                        + hintSuffix(errcode));
            }
            long expiresIn = json.getLong("expires_in", 7200L);
            TokenCache fresh = new TokenCache(token, now + Math.max(60L, expiresIn - TOKEN_SAFE_SECONDS));
            TOKEN_CACHE.put(appId, fresh);
            log.info("【微信公众号】access_token 已刷新 appId={} 有效期={}s", appId, expiresIn);
            return fresh;
        }
    }

    /** 错误码中文提示（便于运维直接定位） */
    private String errorHint(int errcode) {
        return switch (errcode) {
            case 40001, 40014, 42001 -> "token失效";
            case 40003 -> "openid无效";
            case 40164 -> "IP不在白名单";
            case 43004 -> "用户未关注公众号";
            case 45009, 45026 -> "接口调用超频";
            case 47003 -> "模板字段内容不合规或超长";
            case -1 -> "响应异常";
            default -> "微信错误";
        };
    }

    private String hintSuffix(int errcode) {
        if (errcode == 40164) {
            return "（当前服务器 IP 不在公众号 IP 白名单，请在 mp.weixin.qq.com 基本配置中添加）";
        }
        return "";
    }

    private JSONObject parse(String resp) {
        if (!StringUtils.hasText(resp)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(resp);
        } catch (Exception e) {
            log.warn("【微信公众号】响应解析失败: {}", resp.length() > 200 ? resp.substring(0, 200) : resp);
            return null;
        }
    }

    private SendResult fail(String msg, long start) {
        return SendResult.builder()
                .success(false)
                .errorMsg(msg)
                .costTime(System.currentTimeMillis() - start)
                .build();
    }

    private static Map<String, Object> value(String v) {
        Map<String, Object> item = new HashMap<>(2);
        item.put("value", v == null ? "" : v);
        return item;
    }

    private static String text(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 依次取第一个非空变量（兼容下划线/驼峰两种标签命名） */
    private static String firstText(Map<String, Object> vars, String... keys) {
        for (String k : keys) {
            Object v = vars.get(k);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static boolean parseBool(Object v, boolean fallback) {
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        if (!StringUtils.hasText(s)) {
            return fallback;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** 按字符（code point）截断，避免 emoji/生僻字破坏模板字段 */
    private static String cut(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s.trim();
        int cpCount = t.codePointCount(0, t.length());
        if (cpCount <= max) {
            return t;
        }
        int end = t.offsetByCodePoints(0, max);
        return t.substring(0, end);
    }

    /** openid 脱敏：前 6 后 4，日志与错误信息中不留完整值 */
    private static String mask(String openid) {
        if (!StringUtils.hasText(openid) || openid.length() <= 10) {
            return "***";
        }
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }

    /** token 缓存项 */
    private record TokenCache(String token, long expireAt) {
    }
}
