package com.netsight.modules.alert.channel;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信公众号 access_token 管理：
 * <ol>
 *   <li>Redis 优先（key netsight:wechat:token:{appId}，提前 300 秒失效）；</li>
 *   <li>本地 ConcurrentHashMap 兜底（Redis 不可用时仍能发）；</li>
 *   <li>40001/40014/42001 由调用方 forceRefresh=true 强制刷新。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatTokenProvider {

    public static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String KEY_PREFIX = "netsight:wechat:token:";
    private static final long SAFE_SECONDS = 300L;

    private static final Map<String, LocalToken> LOCAL = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final WechatHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${netsight.alert.channel.wechat.timeout-ms:5000}")
    private int timeoutMs = 5000;

    public String getToken(String appId, String appSecret, boolean forceRefresh) {
        if (!forceRefresh) {
            String cached = readRedis(appId);
            if (StringUtils.hasText(cached)) {
                return cached;
            }
            LocalToken local = LOCAL.get(appId);
            if (local != null && local.expireAt > System.currentTimeMillis()) {
                return local.token;
            }
        }
        synchronized (LOCKS.computeIfAbsent(appId, k -> new Object())) {
            if (!forceRefresh) {
                String cached = readRedis(appId);
                if (StringUtils.hasText(cached)) {
                    return cached;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "client_credential");
            body.put("appid", appId);
            body.put("secret", appSecret);
            body.put("force_refresh", forceRefresh);
            String resp;
            try {
                resp = httpClient.postJson(STABLE_TOKEN_URL,
                        objectMapper.writeValueAsString(body), timeoutMs);
            } catch (Exception e) {
                throw new IllegalStateException("获取微信 access_token 网络异常: " + e.getMessage(), e);
            }
            JSONObject json = JSONUtil.parseObj(resp);
            String token = json.getStr("access_token");
            if (!StringUtils.hasText(token)) {
                throw new IllegalStateException("获取微信 access_token 失败: "
                        + json.getInt("errcode", -1) + " " + json.getStr("errmsg", ""));
            }
            long expiresIn = json.getLong("expires_in", 7200L);
            long safe = Math.max(60L, expiresIn - SAFE_SECONDS);
            writeRedis(appId, token, safe);
            LOCAL.put(appId, new LocalToken(token, System.currentTimeMillis() + safe * 1000));
            log.info("【微信】access_token 刷新 appId={}...{} 有效期={}s",
                    appId.substring(0, Math.min(4, appId.length())),
                    appId.substring(Math.max(0, appId.length() - 4)), expiresIn);
            return token;
        }
    }

    private String readRedis(String appId) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + appId);
        } catch (Exception e) {
            log.warn("【微信】读取 token 缓存失败，使用本地兜底: {}", e.getMessage());
            return null;
        }
    }

    private void writeRedis(String appId, String token, long seconds) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + appId, token, Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.warn("【微信】写入 token 缓存失败，使用本地兜底: {}", e.getMessage());
        }
    }

    private record LocalToken(String token, long expireAt) {
    }
}
