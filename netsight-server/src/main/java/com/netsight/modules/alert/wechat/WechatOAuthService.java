package com.netsight.modules.alert.wechat;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.channel.WechatHttpClient;
import com.netsight.modules.alert.service.NotifyChannelService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 微信公众号网页授权（snsapi_base 静默授权）。
 * 流程：start 生成 state → 微信授权 → callback 用 code 换 openid → 生成一次性 bindTicket。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatOAuthService {

    public static final String STATE_KEY = "netsight:wechat:oauth:state:";
    public static final String STATUS_KEY = "netsight:wechat:bind:status:";
    private static final String AUTHORIZE_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final Set<String> ALLOWED_REDIRECTS = Set.of(
            "/m/login", "/m/orders", "/m/order-detail", "/m/out-requests", "/alert/contact");

    private final NotifyChannelService notifyChannelService;
    private final WechatHttpClient httpClient;
    private final WechatBindingService bindingService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${netsight.alert.channel.wechat.oauth-public-base-url:https://api.netsightplus.cn/prod-api}")
    private String defaultPublicBaseUrl;

    @Value("${netsight.alert.channel.wechat.timeout-ms:5000}")
    private int timeoutMs;

    /**
     * 生成微信授权地址。
     * mode=login：登录前绑定，回调生成 bindTicket 回登录页；
     * mode=bind：管理员二维码，回调直接绑定 contactId 并写 bind 状态供 PC 轮询。
     */
    public StartResult start(String redirect, String mode, Long contactId, Long channelId, Long tenantId) {
        Long chId = channelId == null ? 7L : channelId;
        Map<String, Object> ch = resolveWechatChannel(chId);
        Map<String, Object> cfg = configOf(ch);
        String appId = str(cfg.get("appId"));
        if (!StringUtils.hasText(appId)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信公众号通道未配置 appId");
        }
        String safeRedirect = ALLOWED_REDIRECTS.contains(redirect) ? redirect : "/m/login";
        String state = random32();
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("redirect", safeRedirect);
        stateData.put("mode", StringUtils.hasText(mode) ? mode : "login");
        stateData.put("contactId", contactId);
        stateData.put("tenantId", tenantId != null ? tenantId : ch.get("tenantId"));
        stateData.put("channelId", chId);
        if ("bind".equals(mode)) {
            stateData.put("pollTicket", random32());
        }
        save(STATE_KEY + state, stateData, Duration.ofMinutes(10));

        String publicBase = StringUtils.hasText(str(cfg.get("oauthPublicBaseUrl")))
                ? str(cfg.get("oauthPublicBaseUrl")) : defaultPublicBaseUrl;
        String callback = trimTrailingSlash(publicBase) + "/wechat/oauth/callback";
        String url = AUTHORIZE_URL + "?appid=" + enc(appId)
                + "&redirect_uri=" + enc(callback)
                + "&response_type=code&scope=snsapi_base"
                + "&state=" + state + "#wechat_redirect";
        Object pollTicket = stateData.get("pollTicket");
        return new StartResult(url, pollTicket == null ? "" : String.valueOf(pollTicket));
    }

    /**
     * 微信回调：校验 state、code 换 openid、生成 ticket/直接绑定，返回 302 目标。
     */
    public String callback(String code, String state) {
        Map<String, Object> stateData = readAndDelete(STATE_KEY + state);
        if (stateData == null) {
            return errorRedirect(null, "STATE_EXPIRED", "授权已过期，请重新进入。");
        }
        String mode = str(stateData.get("mode"));
        String redirect = str(stateData.get("redirect"));
        if (!StringUtils.hasText(code)) {
            return errorRedirect(mode, "NO_CODE", "微信授权未返回 code，请重新进入。");
        }
        Long channelId = longOf(stateData.get("channelId"));
        Map<String, Object> cfg = configOf(resolveWechatChannel(channelId));
        String appId = str(cfg.get("appId"));
        String appSecret = str(cfg.get("appSecret"));
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return errorRedirect(mode, "CHANNEL_CONFIG", "微信公众号通道凭据不完整。");
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("appid", appId);
            params.put("secret", appSecret);
            params.put("code", code);
            params.put("grant_type", "authorization_code");
            String body = httpClient.get(TOKEN_URL, params, timeoutMs);
            Map<String, Object> resp = objectMapper.readValue(body, new TypeReference<>() {
            });
            String openid = str(resp.get("openid"));
            if (!StringUtils.hasText(openid)) {
                log.warn("微信网页授权换 openid 失败: {}", body);
                return errorRedirect(mode, "OAUTH_FAILED", "微信授权失败：" + str(resp.get("errmsg")));
            }
            String unionid = str(resp.get("unionid"));
            if ("bind".equals(mode)) {
                Long contactId = longOf(stateData.get("contactId"));
                Long tenantId = longOf(stateData.get("tenantId"));
                WechatBindResult r = bindingService.bindByContact(contactId, tenantId, openid, appId);
                String pollTicket = str(stateData.get("pollTicket"));
                saveStatus(pollTicket, r);
                return appendParam(ALLOWED_REDIRECTS.contains(redirect) ? redirect : "/alert/contact",
                        "wechatBind", r.isBound() ? "1" : "0");
            }
            String ticket = random32();
            bindingService.saveTicket(ticket, openid, appId, unionid, channelId);
            String safeRedirect = ALLOWED_REDIRECTS.contains(redirect) ? redirect : "/m/login";
            return appendParam(safeRedirect, "wechatBindTicket", ticket);
        } catch (Exception e) {
            log.error("微信网页授权回调失败: {}", e.getMessage(), e);
            return errorRedirect(mode, "OAUTH_FAILED", "微信授权失败，请重新进入。");
        }
    }

    /** PC 轮询绑定结果；未回调完成返回 PENDING。 */
    public Map<String, Object> bindStatus(String ticket) {
        Map<String, Object> empty = new HashMap<>();
        empty.put("status", "PENDING");
        empty.put("bound", false);
        empty.put("message", "");
        if (!StringUtils.hasText(ticket)) {
            return empty;
        }
        Map<String, Object> data = read(STATUS_KEY + ticket);
        return data == null ? empty : data;
    }

    private void saveStatus(String pollTicket, WechatBindResult r) {
        if (!StringUtils.hasText(pollTicket)) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("status", r.isBound() ? "BOUND" : (r.getReason() == null ? "FAIL" : r.getReason()));
        data.put("bound", r.isBound());
        data.put("message", r.getMessage() == null ? "" : r.getMessage());
        save(STATUS_KEY + pollTicket, data, Duration.ofMinutes(5));
    }

    private Map<String, Object> resolveWechatChannel(Long channelId) {
        List<Map<String, Object>> list = notifyChannelService.resolveChannels(List.of(channelId));
        if (list.isEmpty() || !"wechat".equals(str(list.get(0).get("channelType")))) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信公众号通道不可用");
        }
        return list.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOf(Map<String, Object> ch) {
        Object cfg = ch == null ? null : ch.get("config");
        return cfg instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private void save(String key, Map<String, Object> value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "写入微信授权缓存失败");
        }
    }

    private Map<String, Object> read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取微信授权缓存失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readAndDelete(String key) {
        Map<String, Object> data = read(key);
        redis.delete(key);
        return data;
    }

    private String errorRedirect(String mode, String reason, String message) {
        String base = "bind".equals(mode) ? "/alert/contact" : "/m/login";
        String param = "bind".equals(mode) ? "wechatBindError" : "wechatOauthError";
        return appendParam(base, param, reason);
    }

    private String appendParam(String url, String key, String value) {
        return url + (url.contains("?") ? "&" : "?") + key + "=" + enc(value);
    }

    private String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String random32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Long longOf(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class StartResult {
        private final String url;
        private final String ticket;
    }
}
