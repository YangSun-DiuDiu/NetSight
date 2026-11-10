package com.netsight.modules.alert.channel;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信公众号（服务号）模板消息通道适配器（channelType = wechat）。
 *
 * <p>支持模板描述符（WechatTemplatePayload）、逐人发送结果、单人重试、Redis 幂等去重、
 * 固定窗口频控与 mock。content 为空时回落历史 5 字段，保证 115 现有离线模板不回归。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatMpChannelSender implements NotificationChannelSender {

    public static final String CHANNEL_TYPE = "wechat";

    private static final String TEMPLATE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send";
    private static final String DEFAULT_JUMP_URL = "http://www.netsightplus.cn/m/orders";
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RATE_LIMIT = 4;
    private static final int DEFAULT_DEDUP_HOURS = 24;

    private final WechatTokenProvider tokenProvider;
    private final WechatTemplatePayload payload;
    private final WechatHttpClient httpClient;
    private final StringRedisTemplate redisTemplate;

    @Value("${netsight.alert.channel.wechat.mock-mode:false}")
    private boolean mockMode;

    @Value("${netsight.alert.channel.wechat.timeout-ms:5000}")
    private int timeoutMs;

    @Value("${netsight.alert.channel.wechat.jump-url:" + DEFAULT_JUMP_URL + "}")
    private String defaultJumpUrl;

    @Value("${netsight.alert.retry-intervals:1000,2000}")
    private String defaultRetryIntervals;

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
                ? Map.of() : request.getChannelConfig();
        try {
            String appId = text(cfg, "appId");
            String appSecret = text(cfg, "appSecret");
            String fallbackTemplateId = text(cfg, "templateId");
            if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
                return fail("微信公众号通道实例缺少 appId / appSecret 配置", start);
            }

            Set<String> receivers = new LinkedHashSet<>();
            if (request.getReceiverList() != null) {
                for (String r : request.getReceiverList()) {
                    if (StringUtils.hasText(r)) {
                        receivers.add(r.trim());
                    }
                }
            }
            if (receivers.isEmpty()) {
                return fail("无有效微信接收人（联系人的 wechat_openid 为空）", start);
            }

            boolean mock = parseBool(cfg.get("mockMode"), mockMode);
            boolean manualSend = !StringUtils.hasText(request.getBizId());
            WechatTemplatePayload.Rendered rendered = payload.render(request, fallbackTemplateId);
            if (!StringUtils.hasText(rendered.templateId())) {
                return fail("微信公众号模板 ID 为空", start);
            }

            if (mock) {
                List<RecipientResult> mockResults = new ArrayList<>();
                for (String openid : receivers) {
                    mockResults.add(RecipientResult.builder()
                            .receiver(mask(openid)).success(true).errcode(0).build());
                }
                log.info("【微信公众号 MOCK】appId={} 接收人数={} 模板={}",
                        appId, receivers.size(), rendered.templateId());
                return SendResult.builder()
                        .success(true)
                        .retryable(false)
                        .templateId(rendered.templateId())
                        .renderedContent(rendered.summary())
                        .recipientResults(mockResults)
                        .thirdPartyMsgId("MOCK")
                        .costTime(System.currentTimeMillis() - start)
                        .build();
            }

            String jumpUrl = resolveJumpUrl(request, cfg, rendered.url());
            String token = null;
            List<RecipientResult> results = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            String lastMsgId = null;
            int successCount = 0;
            int duplicateCount = 0;
            int rateLimited = 0;
            int appMismatch = 0;

            int perMinute = intValue(cfg.get("rateLimitPerMinute"), DEFAULT_RATE_LIMIT);
            int dedupHours = intValue(cfg.get("dedupTtlHours"), DEFAULT_DEDUP_HOURS);
            long[] intervals = parseIntervals(cfg.get("retryIntervals"));

            for (String openid : receivers) {
                String receiverAppId = receiverAppId(request, openid);
                if (StringUtils.hasText(receiverAppId) && !receiverAppId.equals(appId)) {
                    appMismatch++;
                    results.add(RecipientResult.builder().receiver(mask(openid))
                            .success(false).skipped(true).skipReason("app_id_mismatch").build());
                    continue;
                }

                String dedupeKey = dedupeKey(request, appId, rendered.templateId(), openid);
                boolean locked = false;
                if (!manualSend) {
                    locked = acquireLock(dedupeKey);
                    if (!locked) {
                        duplicateCount++;
                        results.add(RecipientResult.builder().receiver(mask(openid))
                                .success(false).skipped(true).skipReason("duplicate").build());
                        continue;
                    }
                }

                if (!allowRate(appId, openid, perMinute)) {
                    rateLimited++;
                    releaseLock(dedupeKey, locked);
                    results.add(RecipientResult.builder().receiver(mask(openid))
                            .success(false).skipped(true).skipReason("rate_limit").build());
                    continue;
                }

                if (token == null) {
                    token = tokenProvider.getToken(appId, appSecret, false);
                }

                Attempt attempt = sendOne(appId, appSecret, openid, rendered, jumpUrl,
                        token, intervals);
                if (attempt.success) {
                    token = attempt.token;
                    successCount++;
                    lastMsgId = attempt.msgId;
                } else {
                    if (StringUtils.hasText(attempt.token)) {
                        token = attempt.token;
                    }
                    failures.add(mask(openid) + ":" + attempt.errcode + "("
                            + errorHint(attempt.errcode) + ")");
                }
                results.add(RecipientResult.builder()
                        .receiver(mask(openid))
                        .success(attempt.success)
                        .errcode(attempt.errcode)
                        .errmsg(attempt.errmsg)
                        .retryCount(attempt.retryCount)
                        .skipped(false)
                        .build());
                if (attempt.success) {
                    markSent(dedupeKey, dedupHours);
                } else {
                    releaseLock(dedupeKey, locked);
                }
            }

            int nonDuplicate = successCount + failures.size() + rateLimited + appMismatch;
            boolean allSuccess = failures.isEmpty() && rateLimited == 0 && appMismatch == 0
                    && successCount == nonDuplicate;
            String errorMsg = null;
            if (successCount == 0 && duplicateCount == results.size()) {
                allSuccess = true;
                errorMsg = "全部接收人已发送过，跳过";
            } else if (rateLimited > 0 && successCount == 0 && failures.isEmpty()
                    && appMismatch == 0) {
                allSuccess = false;
                errorMsg = "触发微信频控，未送达";
            } else if (!allSuccess) {
                errorMsg = "微信模板消息部分/全部失败: 成功=" + successCount
                        + "，失败=" + failures.size() + "，频控=" + rateLimited
                        + "，appId不匹配=" + appMismatch
                        + (failures.isEmpty() ? "" : "；" + String.join("; ", failures));
            }
            if (!failures.isEmpty()) {
                log.warn("【微信公众号】部分接收人发送失败: {}", String.join("; ", failures));
            }
            return SendResult.builder()
                    .success(allSuccess)
                    .retryable(false)
                    .templateId(rendered.templateId())
                    .renderedContent(rendered.summary())
                    .recipientResults(results)
                    .errorMsg(errorMsg)
                    .thirdPartyMsgId(lastMsgId)
                    .costTime(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("【微信公众号】发送异常: {}", e.getMessage(), e);
            return fail(e.getMessage(), start);
        }
    }

    @Override
    public boolean healthCheck(Map<String, Object> config) {
        if (config == null) {
            return false;
        }
        String templateId = text(config, "templateId");
        return StringUtils.hasText(text(config, "appId"))
                && StringUtils.hasText(text(config, "appSecret"))
                && (StringUtils.hasText(templateId)
                    || StringUtils.hasText(text(config, "content")));
    }

    // ==================== 内部实现 ====================

    private Attempt sendOne(String appId, String appSecret, String openid,
                            WechatTemplatePayload.Rendered rendered, String jumpUrl,
                            String token, long[] intervals) {
        String body = buildPayload(openid, rendered, jumpUrl).toString();
        int retry = 0;
        String currentToken = token;
        int errcode = -1;
        String errmsg = "空响应";
        String msgId = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String resp = httpClient.postJson(
                        TEMPLATE_SEND_URL + "?access_token=" + currentToken, body, timeoutMs);
                JSONObject json = parse(resp);
                errcode = json == null ? -1 : json.getInt("errcode", -1);
                errmsg = json == null ? "返回空响应" : json.getStr("errmsg", "");
                if (errcode == 0) {
                    msgId = json.getStr("msgid");
                    return new Attempt(true, errcode, errmsg, retry, msgId, currentToken);
                }
                if (errcode == 40001 || errcode == 40014 || errcode == 42001) {
                    currentToken = tokenProvider.getToken(appId, appSecret, true);
                }
                if (!retryable(errcode)) {
                    return new Attempt(false, errcode, errmsg, retry, null, currentToken);
                }
            } catch (Exception e) {
                errcode = -1;
                errmsg = "网络异常: " + e.getMessage();
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(intervals, retry);
                retry++;
            }
        }
        return new Attempt(false, errcode, errmsg, retry, null, currentToken);
    }

    private static JSONObject buildPayload(String openid, WechatTemplatePayload.Rendered rendered,
                                           String jumpUrl) {
        JSONObject payload = new JSONObject();
        payload.set("touser", openid);
        payload.set("template_id", rendered.templateId());
        if (StringUtils.hasText(jumpUrl)) {
            payload.set("url", jumpUrl);
        }
        payload.set("data", rendered.data());
        return payload;
    }

    private boolean retryable(int errcode) {
        return errcode == -1 || errcode == 40001 || errcode == 40014 || errcode == 42001;
    }

    private void sleep(long[] intervals, int retry) {
        long ms = retry < intervals.length ? intervals[retry] : intervals[intervals.length - 1];
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long[] parseIntervals(Object cfgValue) {
        String s = cfgValue == null ? defaultRetryIntervals : String.valueOf(cfgValue);
        if (!StringUtils.hasText(s)) {
            s = "1000,2000";
        }
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (Exception e) {
                out[i] = 1000L;
            }
        }
        return out.length == 0 ? new long[]{1000L, 2000L} : out;
    }

    private String resolveJumpUrl(SendRequest request, Map<String, Object> cfg,
                                  String descriptorUrl) {
        if (validUrl(descriptorUrl)) {
            return descriptorUrl;
        }
        Object extraUrl = request.getExtra() == null ? null : request.getExtra().get("wechatUrl");
        if (extraUrl != null && validUrl(String.valueOf(extraUrl))) {
            return String.valueOf(extraUrl);
        }
        String cfgUrl = text(cfg, "url");
        if (validUrl(cfgUrl)) {
            return cfgUrl;
        }
        return StringUtils.hasText(defaultJumpUrl) && validUrl(defaultJumpUrl)
                ? defaultJumpUrl : DEFAULT_JUMP_URL;
    }

    private static boolean validUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        String s = url.trim();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private String receiverAppId(SendRequest request, String openid) {
        Map<String, Object> extra = request.getExtra();
        if (extra == null) {
            return null;
        }
        Object v = extra.get("receiverAppIds");
        if (v instanceof Map<?, ?> map) {
            Object appId = map.get(openid);
            return appId == null ? null : String.valueOf(appId);
        }
        return null;
    }

    private String dedupeKey(SendRequest request, String appId, String templateId, String openid) {
        Object channelId = request.getChannelId() == null ? appId : request.getChannelId();
        return "netsight:notify:dedupe:" + (request.getTenantId() == null ? 0 : request.getTenantId())
                + ":" + channelId + ":" + request.getBizId() + ":" + templateId
                + ":" + hash(openid);
    }

    private boolean acquireLock(String key) {
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(key, "LOCK", Duration.ofSeconds(60));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("【微信公众号】Redis 去重不可用，按未重复处理: {}", e.getMessage());
            return true;
        }
    }

    private void markSent(String key, int dedupHours) {
        try {
            redisTemplate.opsForValue().set(key, "OK", Duration.ofHours(Math.max(1, dedupHours)));
        } catch (Exception e) {
            log.warn("【微信公众号】写去重标记失败: {}", e.getMessage());
        }
    }

    private void releaseLock(String key, boolean locked) {
        if (!locked) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("【微信公众号】释放去重锁失败: {}", e.getMessage());
        }
    }

    private boolean allowRate(String appId, String openid, int perMinute) {
        if (perMinute <= 0) {
            return true;
        }
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String key = "netsight:wechat:rate:" + appId + ":" + openid + ":" + minute;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(70));
            }
            return count == null || count <= perMinute;
        } catch (Exception e) {
            log.warn("【微信公众号】Redis 频控不可用，按未超限处理: {}", e.getMessage());
            return true;
        }
    }

    private static String hash(String openid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(openid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(openid.hashCode());
        }
    }

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

    private JSONObject parse(String resp) {
        if (!StringUtils.hasText(resp)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(resp);
        } catch (Exception e) {
            log.warn("【微信公众号】响应解析失败: {}",
                    resp.length() > 200 ? resp.substring(0, 200) : resp);
            return null;
        }
    }

    private SendResult fail(String msg, long start) {
        return SendResult.builder()
                .success(false)
                .retryable(false)
                .errorMsg(msg)
                .costTime(System.currentTimeMillis() - start)
                .build();
    }

    private static String text(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intValue(Object v, int fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
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

    private static String mask(String openid) {
        if (!StringUtils.hasText(openid) || openid.length() <= 10) {
            return "***";
        }
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }

    private record Attempt(boolean success, int errcode, String errmsg,
                           int retryCount, String msgId, String token) {
    }
}
