# 服务号消息推送全面支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把微信公众号（服务号）模板消息从“只能发设备离线单模板”升级为覆盖 8 类事件、模板字段可配置、openid 可绑定、投递可审计的通用通道。

**Architecture:** `NotificationTemplate.content` 对 `channelType=wechat` 存 JSON 描述符；`WechatTemplatePayload` 负责解析/渲染/截断；`WechatMpChannelSender` 逐 openid 发送并做逐人重试/去重/频控；`WechatOAuthService` 在 H5 登录前用 `snsapi_base` 换 openid，登录成功后只绑定已登记且启用的 `notify_contact`，不自动创建联系人。

**Tech Stack:** Spring Boot 3.4 / Java 21 / MyBatis-Plus / Redis / Jackson 3（`tools.jackson.databind.ObjectMapper`）/ Hutool HTTP / JUnit 5 + Mockito / Vue 2 + Vant + Element UI

**Spec:** `docs/superpowers/specs/2026-11-10-wechat-mp-full-support-design.md`

## Global Constraints

- 分支：`feature/wechat-mp-full-support`，基线 `bfdcf47`；不混入 `feature/repairer-user-account-binding`。
- 只改测试环境 `115.159.194.149`；生产 `1.94.26.126` 全程不触碰。
- 首次登录手机号查不到 `notify_contact`：**不插入、不自动创建**；只绑 `status=1` 的唯一已登记联系人；登录不阻断。
- 微信模板字段截断：`thing/const/first/remark` 20、`phrase/letter/symbol` 5、`character_string/number` 32、`amount/date` 10、`time` 20。
- 微信接口：`https://api.weixin.qq.com/cgi-bin/stable_token`、`/cgi-bin/message/template/send`、`/sns/oauth2/access_token`、`https://open.weixin.qq.com/connect/oauth2/authorize`。
- 现有模板 43 的模板 ID：`-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U`；AppID：`wx38b4e7eb23e5e249`；AppSecret 只在渠道配置/环境变量中，不入库、不写日志。
- 构建命令必须用 `mvn clean package -DskipTests`；115 重启必须走 `/opt/nams-server/start.sh`。
- Git 只 `git add -- <指定路径>`，禁止 `git add -A`。

---

### Task 1: 数据模型与 DDL

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/channel/SendRequest.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/channel/SendResult.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/channel/RecipientResult.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/entity/NotificationLog.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/entity/NotificationContact.java`
- Create: `docs/sql/wechat_mp_full_support_migration.sql`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/channel/SendResultTest.java`

**Interfaces:**
- Produces: `SendRequest.channelId`、`SendResult.retryable/templateId/renderedContent/recipientResults`、`RecipientResult`、`NotificationLog.channelId/templateId/recipientResultsJson`、`NotificationContact.wechatAppId/wechatBoundAt`。

- [ ] **Step 1: 写失败测试（模型字段存在）**

```java
@Test
void sendResultCarriesRecipientResults() {
    RecipientResult r = RecipientResult.builder()
            .receiver("o6****1234").success(true).retryCount(1).build();
    SendResult result = SendResult.builder()
            .success(false).retryable(false).templateId("tpl")
            .recipientResults(List.of(r)).build();
    assertEquals("tpl", result.getTemplateId());
    assertEquals(1, result.getRecipientResults().size());
    assertFalse(result.isRetryable());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=SendResultTest test`
Expected: `RecipientResult` 不存在，编译失败。

- [ ] **Step 3: 扩展模型**

`SendRequest.java` 增加：

```java
/** 渠道实例 ID（用于日志与去重 key；可为空） */
private Long channelId;
```

`RecipientResult.java`：

```java
package com.netsight.modules.alert.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecipientResult {
    private String receiver;
    private boolean success;
    private Integer errcode;
    private String errmsg;
    private int retryCount;
    private boolean skipped;
    private String skipReason;
}
```

`SendResult.java` 增加：

```java
/** 是否允许通道层整批重试；wechat 逐人重试后置 false */
private boolean retryable = true;
/** 实际使用的微信模板 ID */
private String templateId;
/** 渲染后的可读内容（写日志） */
private String renderedContent;
/** 逐人结果；非空时通道层不得整批重试 */
private List<RecipientResult> recipientResults;
```

`NotificationLog.java` 增加：

```java
private Long channelId;
private String templateId;
private String recipientResultsJson;
```

`NotificationContact.java` 增加：

```java
private String wechatAppId;
private LocalDateTime wechatBoundAt;
```

- [ ] **Step 4: 写幂等 DDL**

`docs/sql/wechat_mp_full_support_migration.sql`：

```sql
-- notification_log 三列
SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'channel_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN channel_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'template_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN template_id VARCHAR(128) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notification_log' AND column_name = 'recipient_results_json');
SET @sql := IF(@col = 0, 'ALTER TABLE notification_log ADD COLUMN recipient_results_json TEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- notify_contact 两列
SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notify_contact' AND column_name = 'wechat_app_id');
SET @sql := IF(@col = 0, 'ALTER TABLE notify_contact ADD COLUMN wechat_app_id VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notify_contact' AND column_name = 'wechat_bound_at');
SET @sql := IF(@col = 0, 'ALTER TABLE notify_contact ADD COLUMN wechat_bound_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

- [ ] **Step 5: 编译并提交**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -DskipTests compile`
Expected: BUILD SUCCESS。

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/channel/SendRequest.java `
  netsight-server/src/main/java/com/netsight/modules/alert/channel/SendResult.java `
  netsight-server/src/main/java/com/netsight/modules/alert/channel/RecipientResult.java `
  netsight-server/src/main/java/com/netsight/modules/alert/entity/NotificationLog.java `
  netsight-server/src/main/java/com/netsight/modules/alert/entity/NotificationContact.java `
  docs/sql/wechat_mp_full_support_migration.sql `
  netsight-server/src/test/java/com/netsight/modules/alert/channel/SendResultTest.java
git commit -m "feat(alert): extend send result and log models for wechat recipients"
```

---

### Task 2: 模板描述符解析器

**Files:**
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatTemplatePayload.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatTemplatePayloadTest.java`

**Interfaces:**
- Consumes: `SendRequest.content/contentVars/extra`。
- Produces: `WechatTemplatePayload.resolve(String content, String fallbackTemplateId)`、`render(SendRequest request, String templateId)`、`defaultTemplateId(String content, String fallback)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void parsesJsonAndRendersFields() {
    WechatTemplatePayload p = new WechatTemplatePayload(new ObjectMapper());
    String content = """
      {"templateId":"tpl-1","url":"http://x","fields":[
        {"key":"thing2","value":"{{device_name}}","default":"未知设备"},
        {"key":"phrase9","value":"{{event_state}}","default":"告警"}
      ]}
      """;
    SendRequest req = SendRequest.builder()
        .content(content).contentVars(Map.of("device_name", "核心交换机", "event_type", "device_offline"))
        .bizId("b1").tenantId(7L).build();
    WechatTemplatePayload.Rendered r = p.render(req, "fallback");
    assertEquals("tpl-1", r.templateId());
    assertEquals("核心交换机", r.data().get("thing2").get("value"));
    assertEquals("离线", r.data().get("phrase9").get("value"));
}

@Test
void emptyContentFallsBackToDefaultFiveFields() {
    WechatTemplatePayload p = new WechatTemplatePayload(new ObjectMapper());
    SendRequest req = SendRequest.builder().content("").contentVars(Map.of()).build();
    WechatTemplatePayload.Rendered r = p.render(req, "tpl-43");
    assertEquals("tpl-43", r.templateId());
    assertEquals(5, r.data().size());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatTemplatePayloadTest test`
Expected: 类不存在，编译失败。

- [ ] **Step 3: 实现解析器**

```java
package com.netsight.modules.alert.channel;

import com.netsight.modules.alert.service.NotificationTemplateService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class WechatTemplatePayload {

    private final ObjectMapper objectMapper;

    private static final Map<String, String> STATE = Map.ofEntries(
            Map.entry("device_offline", "设备离线"),
            Map.entry("device_line_abnormal", "外线异常"),
            Map.entry("device_recovered", "已恢复"),
            Map.entry("device_cpu_high", "CPU过高"),
            Map.entry("device_mem_high", "内存过高"),
            Map.entry("order_dispatch", "待接单"),
            Map.entry("stock_low", "库存不足"),
            Map.entry("manual_notify", "通知")
    );
    private static final Map<String, String> TITLE = Map.ofEntries(
            Map.entry("device_offline", "设备离线告警"),
            Map.entry("device_line_abnormal", "链路异常告警"),
            Map.entry("device_recovered", "故障恢复通知"),
            Map.entry("device_cpu_high", "CPU使用率告警"),
            Map.entry("device_mem_high", "内存使用率告警"),
            Map.entry("order_dispatch", "工单派发"),
            Map.entry("stock_low", "备件库存预警"),
            Map.entry("manual_notify", "系统通知")
    );

    public Rendered render(SendRequest request, String fallbackTemplateId) {
        Map<String, Object> vars = request.getContentVars() == null
                ? new HashMap<>() : new HashMap<>(request.getContentVars());
        enrich(vars, request);
        Descriptor descriptor = parse(request.getContent());
        String templateId = descriptor != null && StringUtils.hasText(descriptor.templateId)
                ? descriptor.templateId : fallbackTemplateId;
        List<Field> fields = descriptor != null && descriptor.fields != null && !descriptor.fields.isEmpty()
                ? descriptor.fields : defaultFields();
        Map<String, Object> data = new LinkedHashMap<>();
        for (Field f : fields) {
            String value = NotificationTemplateService.render(f.value, vars);
            if (!StringUtils.hasText(value)) {
                value = f.defaultValue == null ? "" : f.defaultValue;
            }
            data.put(f.key, Map.of("value", cut(value, limit(f.key))));
        }
        String url = descriptor != null && StringUtils.hasText(descriptor.url)
                ? descriptor.url : null;
        String summary = "模板=" + templateId + "；"
                + data.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().get("value"))
                    .reduce((a, b) -> a + "，" + b).orElse("");
        return new Rendered(templateId, url, data, summary);
    }

    public String resolveTemplateId(String content, String fallback) {
        Descriptor d = parse(content);
        return d != null && StringUtils.hasText(d.templateId) ? d.templateId : fallback;
    }

    private Descriptor parse(String content) {
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) return null;
        try {
            return objectMapper.readValue(content, Descriptor.class);
        } catch (Exception e) {
            log.warn("微信模板描述符解析失败，回落默认 5 字段: {}", e.getMessage());
            return null;
        }
    }

    private List<Field> defaultFields() {
        return List.of(
                new Field("thing2", "{{device_name}}", "未知设备"),
                new Field("phrase9", "{{event_state}}", "告警"),
                new Field("character_string11", "{{device_no}}", "-"),
                new Field("thing17", "{{location}}", "未登记位置"),
                new Field("phrase20", "{{severity_text}}", "一般")
        );
    }

    private void enrich(Map<String, Object> vars, SendRequest req) {
        String eventType = first(vars, "event_type", "eventType");
        vars.putIfAbsent("event_state", STATE.getOrDefault(eventType, "告警"));
        vars.putIfAbsent("event_title", TITLE.getOrDefault(eventType, "系统通知"));
        vars.putIfAbsent("severity_text", severityText(first(vars, "severity")));
        vars.putIfAbsent("device_no", firstNonEmpty(
                first(vars, "device_code", "deviceCode"),
                first(vars, "device_ip", "deviceIp"),
                req.getBizId()));
        vars.putIfAbsent("biz_id", req.getBizId() == null ? "" : req.getBizId());
        vars.putIfAbsent("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        vars.putIfAbsent("content", req.getContent() == null ? "" : req.getContent());
    }

    static String severityText(String severity) {
        if (!StringUtils.hasText(severity)) return "一般";
        return switch (severity.toLowerCase()) {
            case "critical", "fatal", "emergency" -> "严重";
            case "warning", "warn", "major" -> "重要";
            case "resolved", "ok", "recovered" -> "已恢复";
            default -> "一般";
        };
    }

    static String cut(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        int cp = t.codePointCount(0, t.length());
        if (cp <= max) return t;
        return t.substring(0, t.offsetByCodePoints(0, max));
    }

    static int limit(String key) {
        if (key == null) return 20;
        if (key.startsWith("thing") || key.startsWith("const")
                || key.startsWith("first") || key.startsWith("remark")) return 20;
        if (key.startsWith("phrase") || key.startsWith("letter")
                || key.startsWith("symbol")) return 5;
        if (key.startsWith("character_string") || key.startsWith("number")) return 32;
        if (key.startsWith("amount") || key.startsWith("date")) return 10;
        if (key.startsWith("time")) return 20;
        return 20;
    }

    private static String first(Map<String, Object> vars, String... keys) {
        for (String k : keys) {
            Object v = vars.get(k);
            if (v != null && StringUtils.hasText(String.valueOf(v))) return String.valueOf(v).trim();
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (StringUtils.hasText(v)) return v;
        return "";
    }

    @Data
    public static class Descriptor {
        private String templateId;
        private String url;
        private List<Field> fields;
    }

    @Data
    public static class Field {
        private String key;
        private String value;
        private String defaultValue;

        public Field() {}
        public Field(String key, String value, String defaultValue) {
            this.key = key; this.value = value; this.defaultValue = defaultValue;
        }
    }

    @Builder
    public record Rendered(String templateId, String url,
                           Map<String, Object> data, String summary) {}
}
```

> 注意：Jackson 3 默认字段名是 `defaultValue`，但 JSON 里是 `default`。在 `Field` 上加 `@JsonProperty("default")`（`tools.jackson.databind.annotation.JsonProperty` 或 `com.fasterxml.jackson.annotation.JsonProperty`，以项目现有依赖为准）。如果 Jackson 3 注解包不同，改用 `@JsonAlias("default")` 并保留 `defaultValue` 字段名。

- [ ] **Step 4: 跑测试确认通过**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatTemplatePayloadTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatTemplatePayload.java `
  netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatTemplatePayloadTest.java
git commit -m "feat(alert): add configurable wechat template payload"
```

---

### Task 3: Token Provider 与 HTTP 客户端

**Files:**
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatHttpClient.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/channel/HutoolWechatHttpClient.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatTokenProvider.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatTokenProviderTest.java`

**Interfaces:**
- Produces: `WechatHttpClient.get(String url, Map<String,String> params, int timeoutMs)`、`postJson(String url, String body, int timeoutMs)`；`WechatTokenProvider.getToken(String appId, String appSecret, boolean forceRefresh)`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void cachesTokenInRedis() {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("netsight:wechat:token:app1")).thenReturn("cached-token");
    WechatTokenProvider p = new WechatTokenProvider(redis, http, mapper);
    assertEquals("cached-token", p.getToken("app1", "secret", false));
    verifyNoInteractions(http);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatTokenProviderTest test`
Expected: 类不存在。

- [ ] **Step 3: 实现**

`WechatHttpClient`：

```java
public interface WechatHttpClient {
    String get(String url, Map<String, String> params, int timeoutMs);
    String postJson(String url, String body, int timeoutMs);
}
```

`HutoolWechatHttpClient`：

```java
@Component
public class HutoolWechatHttpClient implements WechatHttpClient {
    @Override
    public String get(String url, Map<String, String> params, int timeoutMs) {
        return HttpUtil.get(url, params, timeoutMs);
    }
    @Override
    public String postJson(String url, String body, int timeoutMs) {
        return HttpUtil.post(url, body, timeoutMs);
    }
}
```

`WechatTokenProvider`：

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatTokenProvider {

    public static final String URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String KEY = "netsight:wechat:token:";
    private static final Map<String, LocalToken> LOCAL = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final WechatHttpClient http;
    private final ObjectMapper objectMapper;

    @Value("${netsight.alert.channel.wechat.timeout-ms:5000}")
    private int timeoutMs;

    public String getToken(String appId, String appSecret, boolean forceRefresh) {
        if (!forceRefresh) {
            String cached = readRedis(appId);
            if (StringUtils.hasText(cached)) return cached;
            LocalToken local = LOCAL.get(appId);
            if (local != null && local.expireAt > System.currentTimeMillis()) return local.token;
        }
        synchronized (LOCKS.computeIfAbsent(appId, k -> new Object())) {
            if (!forceRefresh) {
                String cached = readRedis(appId);
                if (StringUtils.hasText(cached)) return cached;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "client_credential");
            body.put("appid", appId);
            body.put("secret", appSecret);
            body.put("force_refresh", forceRefresh);
            String resp;
            try {
                resp = http.postJson(URL, objectMapper.writeValueAsString(body), timeoutMs);
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
            long safe = Math.max(60L, expiresIn - 300L);
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
            return redis.opsForValue().get(KEY + appId);
        } catch (Exception e) {
            log.warn("【微信】读取 token 缓存失败，使用本地兜底: {}", e.getMessage());
            return null;
        }
    }

    private void writeRedis(String appId, String token, long seconds) {
        try {
            redis.opsForValue().set(KEY + appId, token, Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.warn("【微信】写入 token 缓存失败，使用本地兜底: {}", e.getMessage());
        }
    }

    private record LocalToken(String token, long expireAt) {}
}
```

- [ ] **Step 4: 跑测试**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatTokenProviderTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatHttpClient.java `
  netsight-server/src/main/java/com/netsight/modules/alert/channel/HutoolWechatHttpClient.java `
  netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatTokenProvider.java `
  netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatTokenProviderTest.java
git commit -m "feat(alert): add wechat token provider and http client"
```

---

### Task 4: 通用发送器（逐人结果 / 重试 / 去重 / 频控）

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatMpChannelSender.java`（整体重写）
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatMpChannelSenderTest.java`

**Interfaces:**
- Consumes: `WechatTemplatePayload.render(...)`、`WechatTokenProvider.getToken(...)`、`StringRedisTemplate`。
- Produces: `SendResult` 含 `recipientResults`；每个 openid 独立结果；`success=false` 表示部分失败。

- [ ] **Step 1: 写失败测试（两个 openid：一个成功、一个 43004）**

```java
@Test
void partialFailureReturnsPerRecipientResults() {
    when(http.postJson(anyString(), anyString(), anyInt()))
        .thenReturn("{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"m1\"}")
        .thenReturn("{\"errcode\":43004,\"errmsg\":\"require subscribe\"}");
    WechatMpChannelSender sender = new WechatMpChannelSender(
        tokenProvider, payload, http, redis, mapper);
    SendResult result = sender.send(SendRequest.builder()
        .receiverList(List.of("openid-a", "openid-b"))
        .bizId("biz-1").tenantId(7L).channelId(7L)
        .content("").contentVars(Map.of("event_type", "device_offline"))
        .channelConfig(Map.of("appId", "app", "appSecret", "sec", "templateId", "tpl"))
        .build());
    assertFalse(result.isSuccess());
    assertEquals(2, result.getRecipientResults().size());
    assertTrue(result.getRecipientResults().get(0).isSuccess());
    assertEquals(43004, result.getRecipientResults().get(1).getErrcode());
    assertFalse(result.isRetryable());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatMpChannelSenderTest test`
Expected: 断言失败（当前 `success=true` 部分成功 / 无逐人结果）。

- [ ] **Step 3: 重写 `WechatMpChannelSender`**

关键方法：

```java
@Override
public SendResult send(SendRequest request) {
    long start = System.currentTimeMillis();
    Map<String, Object> cfg = request.getChannelConfig() == null ? Map.of() : request.getChannelConfig();
    String appId = text(cfg, "appId");
    String appSecret = text(cfg, "appSecret");
    String fallbackTemplateId = text(cfg, "templateId");
    if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
        return fail("微信公众号通道实例缺少 appId / appSecret 配置", start);
    }
    WechatTemplatePayload.Rendered rendered = payload.render(request, fallbackTemplateId);
    if (!StringUtils.hasText(rendered.templateId())) {
        return fail("微信模板 ID 未配置", start);
    }
    List<String> receivers = request.getReceiverList() == null ? List.of() : request.getReceiverList();
    if (receivers.isEmpty()) {
        return fail("无有效微信接收人（联系人的 wechat_openid 为空）", start);
    }
    boolean mock = parseBool(cfg.get("mockMode"), globalMock);
    if (mock) {
        return mockResult(receivers, rendered, start);
    }
    String jumpUrl = resolveJumpUrl(request, cfg, rendered.url());
    String token = tokenProvider.getToken(appId, appSecret, false);
    int maxAttempts = 3;
    int rateLimit = parseInt(cfg.get("rateLimitPerMinute"), 4);
    long dedupTtl = parseLong(cfg.get("dedupTtlHours"), 24L);
    int[] intervals = parseIntervals(cfg.get("retryIntervals"));
    List<RecipientResult> results = new ArrayList<>();
    String lastMsgId = null;
    for (String openid : receivers) {
        if (!StringUtils.hasText(openid)) continue;
        if (isDuplicate(request, openid, dedupTtl)) {
            results.add(skip(openid, "duplicate", "同一业务已发送"));
            continue;
        }
        if (isRateLimited(appId, openid, rateLimit)) {
            results.add(skip(openid, "rate_limit", "超过每分钟上限"));
            continue;
        }
        RecipientResult one = sendOne(appId, appSecret, token, openid, rendered, jumpUrl, intervals, maxAttempts);
        results.add(one);
        if (one.isSuccess()) lastMsgId = one.getErrmsg() == null ? lastMsgId : lastMsgId;
    }
    ...
}
```

`sendOne` 必须：

- 每次尝试独立构造 payload：
```java
JSONObject body = new JSONObject();
body.set("touser", openid);
body.set("template_id", rendered.templateId());
if (StringUtils.hasText(jumpUrl)) body.set("url", jumpUrl);
body.set("data", rendered.data());
```
- 调 `http.postJson(TEMPLATE_SEND_URL + "?access_token=" + token, JSONUtil.toJsonStr(body), timeoutMs)`
- `errcode == 0` 成功；
- `40001/40014/42001` 强制刷新 token 后重试一次；
- `retryable` 错误（网络异常、5xx、-1）最多 3 次尝试；
- `40003/43004/47003/40164/45009/45026` 不重试；
- 返回 `RecipientResult`，`errmsg` 里带 `errorHint`。

汇总：

```java
int ok = (int) results.stream().filter(r -> r.isSuccess() && !r.isSkipped()).count();
int fail = (int) results.stream().filter(r -> !r.isSuccess() && !r.isSkipped()).count();
int skip = (int) results.stream().filter(RecipientResult::isSkipped).count();
boolean allSkippedDuplicate = skip > 0 && ok == 0 && fail == 0
        && results.stream().allMatch(r -> "duplicate".equals(r.getSkipReason()));
boolean success = fail == 0 && (ok > 0 || allSkippedDuplicate);
return SendResult.builder()
    .success(success)
    .retryable(false)
    .templateId(rendered.templateId())
    .renderedContent(rendered.summary())
    .recipientResults(results)
    .thirdPartyMsgId(lastMsgId)
    .errorMsg(success ? null : ("微信发送：成功" + ok + "，失败" + fail + "，跳过" + skip))
    .costTime(System.currentTimeMillis() - start)
    .build();
```

去重 key：

```java
String key = "netsight:notify:dedupe:" + tenantId + ":" + channelId + ":" + bizId
        + ":" + templateId + ":" + sha16(openid);
Boolean locked = redis.opsForValue().setIfAbsent(key, "LOCK", Duration.ofSeconds(60));
if (Boolean.FALSE.equals(locked)) return true;
// 发送成功后 set key "OK" dedupTtl
```

频控：

```java
String key = "netsight:wechat:rate:" + appId + ":" + openid + ":"
        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
Long count = redis.opsForValue().increment(key);
if (count != null && count == 1L) redis.expire(key, Duration.ofMinutes(1));
return count != null && count > limit;
```

`bizId` 为空直接返回 `false`，不去重。

- [ ] **Step 4: 跑测试**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatMpChannelSenderTest test`
Expected: PASS（部分失败、逐人结果、不整批重试）。

- [ ] **Step 5: 提交**

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/channel/WechatMpChannelSender.java `
  netsight-server/src/test/java/com/netsight/modules/alert/channel/WechatMpChannelSenderTest.java
git commit -m "feat(alert): per-recipient wechat sending with retry dedupe and rate limit"
```

---

### Task 5: 通道中心集成与业务入口修复

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationChannelService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationContactService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/EventCenterService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/AlertPushService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/SparePartService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/workorder/service/WorkOrderNotifier.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/service/NotificationChannelServiceTest.java`

**Interfaces:**
- Consumes: `SendResult.recipientResults`、`SendResult.templateId/renderedContent`、`NotificationContact.wechatOpenid`。
- Produces: 日志逐人结果、`doneChannelKeys`、手动模板解析、派单 openid、8 类告警映射。

- [ ] **Step 1: 写失败测试（`recipientResults` 非空时不整批重试）**

```java
@Test
void doesNotRetryWholeBatchWhenRecipientResultsPresent() {
    SendResult result = SendResult.builder().success(false).retryable(false)
        .recipientResults(List.of(RecipientResult.builder()
            .receiver("o1").success(false).build()))
        .errorMsg("partial").build();
    when(sender.send(any())).thenReturn(result);
    RetrySendResult r = service.sendWithRetry(sender, request);
    assertEquals(0, r.retryCount());
    verify(sender, times(1)).send(any());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=NotificationChannelServiceTest test`
Expected: 当前会重试 3 次，`retryCount=3`。

- [ ] **Step 3: 修改 `sendWithRetry`**

```java
private RetrySendResult sendWithRetry(NotificationChannelSender sender, SendRequest request) {
    SendResult result = sender.send(request);
    int retry = 0;
    if (!result.isSuccess() && result.getRecipientResults() == null && result.isRetryable()) {
        int[] intervals = parseIntervals();
        while (retry < intervals.length) {
            retry++;
            try { Thread.sleep(intervals[retry - 1]); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            result = sender.send(request);
            if (result.isSuccess() || result.getRecipientResults() != null) break;
        }
    }
    return new RetrySendResult(result, retry);
}
```

- [ ] **Step 4: 日志写入逐人结果**

`sendOneResolved` 和 `sendOne` 的 `SendRequest` 加 `.channelId(channelInstanceId)`；日志加：

```java
logEntry.setChannelId(channelInstanceId);
logEntry.setTemplateId(result.sendResult.getTemplateId());
logEntry.setRecipientResultsJson(result.sendResult.getRecipientResults() == null
        ? null : toJson(result.sendResult.getRecipientResults()));
if ("wechat".equals(channelType) && result.sendResult.getRenderedContent() != null) {
    logEntry.setContent(result.sendResult.getRenderedContent());
}
```

`retryCount` 改为：

```java
int maxRetry = result.sendResult.getRecipientResults() == null ? result.retryCount
        : result.sendResult.getRecipientResults().stream()
            .mapToInt(RecipientResult::getRetryCount).max().orElse(0);
logEntry.setRetryCount(maxRetry);
```

- [ ] **Step 5: `doneChannelTypes` 改 `doneChannelKeys`**

两处：

```java
List<String> doneChannelKeys = new ArrayList<>();
...
String channelKey = channelType + "#" + ch.get("channelId");
if (doneChannelKeys.contains(channelKey)) continue;
...
doneChannelKeys.add(channelKey);
```

降级判断同步用 key，不再按类型跳过第二个同类型实例。

- [ ] **Step 6: 手动发送模板解析**

`dispatchByChannelIds` 开头：

```java
NotificationTemplate mainTemplate = findManualTemplate(event);
```

新增：

```java
private NotificationTemplate findManualTemplate(EventRecord event) {
    String code = switch (event.getEventType()) {
        case "manual_notify" -> "tpl_manual";
        case "stock_low" -> "tpl_stock_low";
        case "order_dispatch" -> "tpl_order_dispatch";
        case "device_offline" -> "tpl_device_offline";
        case "device_line_abnormal" -> "tpl_device_line";
        case "device_recovered" -> "tpl_device_recovered";
        case "device_cpu_high" -> "tpl_device_cpu_high";
        case "device_mem_high" -> "tpl_device_mem_high";
        default -> event.getEventType();
    };
    return templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
            .eq(NotificationTemplate::getTemplateCode, code)
            .eq(NotificationTemplate::getTenantId, event.getTenantId())
            .eq(NotificationTemplate::getEnabled, 1)
            .last("LIMIT 1"));
}
```

`EventCenterService.dispatchManual` 改为：

```java
Map<String, Object> vars = new HashMap<>(buildVars(event));
vars.put("content", event.getContent() == null ? "" : event.getContent());
List<NotificationLog> logs = channelService.dispatch(event, null, contacts, channels, vars, null);
```

- [ ] **Step 7: 派单 openid**

`WorkOrderNotifier`：

- 注入 `NotificationContactService contactService`。
- 主模板查询加 `.eq(NotificationTemplate::getEnabled, 1)`。
- 构造联系人改为：

```java
NotificationContact c = contactService.findByTenantAndPhoneUnique(order.getTenantId(), repairer.getPhone());
if (c == null) {
    log.warn("维修人员[{}]手机号[{}]未登记通知联系人，微信通道跳过，短信照常", repairer.getName(), repairer.getPhone());
    c = new NotificationContact();
    c.setName(repairer.getName());
    c.setMobile(repairer.getPhone());
} else {
    c.setMobile(repairer.getPhone());
}
contacts.add(c);
```

`NotificationContactService` 新增：

```java
public NotificationContact findByTenantAndPhoneUnique(Long tenantId, String phone) {
    List<NotificationContact> list = contactMapper.selectList(new LambdaQueryWrapper<NotificationContact>()
            .eq(NotificationContact::getTenantId, tenantId)
            .eq(NotificationContact::getMobile, phone)
            .eq(NotificationContact::getStatus, 1));
    return list.size() == 1 ? list.get(0) : null;
}
```

- 变量补下划线别名：

```java
vars.put("order_no", order.getOrderNo());
vars.put("device_name", order.getDeviceName());
vars.put("device_ip", order.getDeviceIp());
vars.put("device_type", order.getDeviceType());
vars.put("device_location", order.getDeviceLocation());
vars.put("location", order.getDeviceLocation());
vars.put("fault_desc", vars.get("faultDesc"));
vars.put("repairer_name", repairer.getName());
```

- [ ] **Step 8: 告警映射与备件别名**

`AlertPushService.ALERTNAME_MAP` 增加：

```java
"CPU使用率过高", "device_cpu_high",
"CPU过高", "device_cpu_high",
"CPU高负载", "device_cpu_high",
"内存使用率过高", "device_mem_high",
"内存过高", "device_mem_high",
"内存高负载", "device_mem_high",
```

`SparePartService` 的 labels 已有 `partName/partNo/quantity/safeStock`；在 `EventCenterService.buildVars` 增加下划线别名：

```java
vars.put("part_name", firstNonEmpty(vars.get("partName"), event.getDeviceName()));
vars.put("part_no", vars.get("partNo"));
vars.put("quantity", vars.get("quantity"));
vars.put("safe_stock", vars.get("safeStock"));
vars.put("stock_location", vars.get("location"));
```

- [ ] **Step 9: `resolveReceiversByChannel` appId 过滤**

```java
public List<String> resolveReceiversByChannel(List<NotificationContact> contacts, String channelType) {
    return resolveReceiversByChannel(contacts, channelType, null);
}

public List<String> resolveReceiversByChannel(List<NotificationContact> contacts,
                                               String channelType, String appId) {
    ...
    if (CHANNEL_WECHAT.equals(channelType)) {
        List<NotificationContact> bound = contacts.stream()
                .filter(c -> StringUtils.hasText(c.getWechatOpenid()))
                .filter(c -> !StringUtils.hasText(appId)
                        || !StringUtils.hasText(c.getWechatAppId())
                        || appId.equals(c.getWechatAppId()))
                .toList();
        List<String> openids = bound.stream().map(NotificationContact::getWechatOpenid)
                .distinct().toList();
        int noOpenid = contacts.size() - bound.size();
        if (noOpenid > 0) log.warn("[通知联系人] {} 个联系人未绑定微信openid 或 appId 不匹配，公众号通道将跳过", noOpenid);
        return openids;
    }
}
```

调用方 `NotificationChannelService` 传 `appId`：

```java
String appId = chCfg == null ? null : String.valueOf(chCfg.get("appId"));
contactService.resolveReceiversByChannel(contacts, channelType, appId);
```

- [ ] **Step 10: 跑测试并提交**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=NotificationChannelServiceTest test`
Expected: PASS。

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationChannelService.java `
  netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationContactService.java `
  netsight-server/src/main/java/com/netsight/modules/alert/service/EventCenterService.java `
  netsight-server/src/main/java/com/netsight/modules/alert/service/AlertPushService.java `
  netsight-server/src/main/java/com/netsight/modules/alert/service/SparePartService.java `
  netsight-server/src/main/java/com/netsight/modules/workorder/service/WorkOrderNotifier.java `
  netsight-server/src/test/java/com/netsight/modules/alert/service/NotificationChannelServiceTest.java
git commit -m "feat(alert): integrate wechat recipients into dispatch and business entries"
```

---

### Task 6: OAuth 与绑定服务

**Files:**
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatBindingService.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatOAuthService.java`
- Create: `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatOAuthController.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/wechat/WechatBindingServiceTest.java`

**Interfaces:**
- Consumes: `NotificationContactService`、`NotifyChannelService`、`StringRedisTemplate`、`WechatHttpClient`。
- Produces: `/wechat/oauth/start`、`/wechat/oauth/callback`、`/alert/contact/{id}/wechat/bind-url`、`/alert/contact/wechat/bind-status`、`/alert/contact/{id}/wechat/unbind`；`bindByTicket(tenantId, phone, ticket)` 返回 `WechatBindResult`。

- [ ] **Step 1: 写失败测试（不自动创建）**

```java
@Test
void missingContactDoesNotCreateRow() {
    when(contactService.findByTenantAndPhone(7L, "13900000002")).thenReturn(List.of());
    WechatBindResult r = service.bindByTicket(7L, "13900000002", "ticket-1");
    assertFalse(r.isBound());
    assertEquals("CONTACT_NOT_REGISTERED", r.getReason());
    verify(contactMapper, never()).insert(any());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatBindingServiceTest test`
Expected: 类不存在。

- [ ] **Step 3: 实现绑定服务**

核心方法：

```java
public WechatBindResult bindByTicket(Long tenantId, String phone, String ticket) {
    Ticket t = readTicket(ticket);
    if (t == null) return result(false, "TICKET_EXPIRED", "绑定凭证已过期，请重新从公众号进入。");
    List<NotificationContact> contacts = contactService.findByTenantAndPhone(tenantId, phone);
    if (contacts.isEmpty()) {
        return result(false, "CONTACT_NOT_REGISTERED",
                "当前手机号未登记为通知联系人，请联系管理员在【告警中心 → 通知联系人】中添加并启用后，再重新从公众号进入绑定。");
    }
    List<NotificationContact> enabled = contacts.stream().filter(c -> c.getStatus() != null && c.getStatus() == 1).toList();
    if (enabled.isEmpty()) {
        return result(false, "CONTACT_DISABLED", "该通知联系人已停用，请联系管理员启用后再绑定。");
    }
    if (enabled.size() > 1) {
        return result(false, "CONTACT_AMBIGUOUS", "该手机号存在多个通知联系人，请联系管理员确认唯一联系人。");
    }
    NotificationContact contact = enabled.get(0);
    NotificationContact sameOpenid = contactService.findByTenantAndOpenid(tenantId, t.openid);
    if (sameOpenid != null && !sameOpenid.getId().equals(contact.getId())) {
        return result(false, "OPENID_BOUND_OTHER", "该微信已绑定其他通知联系人，请联系管理员处理。");
    }
    if (StringUtils.hasText(contact.getWechatOpenid())
            && !t.openid.equals(contact.getWechatOpenid())) {
        return result(false, "CONTACT_BOUND_OTHER", "该通知联系人已绑定其他微信，请联系管理员先解绑。");
    }
    contactService.bindWechat(contact.getId(), t.openid, t.appId);
    return result(true, null, "登录成功，微信消息已绑定。");
}
```

结果对象：

```java
@Data
@Builder
public class WechatBindResult {
    private boolean bound;
    private String reason;
    private String message;
}
```

- [ ] **Step 4: 实现 OAuth 服务与控制器**

`WechatOAuthService`：

- `start(redirect, mode, contactId, channelId)`：
  - `Map<String,Object> ch = notifyChannelService.resolveChannels(List.of(channelId)).get(0)`；
  - `state = random32()`；
  - Redis `netsight:wechat:oauth:state:{state}` 存 JSON，TTL 10 分钟；
  - 返回 `https://open.weixin.qq.com/connect/oauth2/authorize?...`。
- `callback(code, state)`：
  - 取 state，删除；
  - `http.get("https://api.weixin.qq.com/sns/oauth2/access_token", params)`；
  - 取 `openid`；
  - 生成 `bindTicket`，Redis 存 `{openid, appId, channelId}`，TTL 10 分钟；
  - 返回 302 到 `redirect + "?wechatBindTicket=" + ticket`。
- 错误统一 302 到 `/m/login?wechatOauthError=...`。

`WechatOAuthController`：

```java
@GetMapping("/start")
public ResponseEntity<Void> start(@RequestParam(defaultValue = "/m/login") String redirect,
                                  @RequestParam(defaultValue = "login") String mode,
                                  @RequestParam(required = false) Long contactId,
                                  @RequestParam(defaultValue = "7") Long channelId) {
    String url = oauthService.start(redirect, mode, contactId, channelId);
    return ResponseEntity.status(302).header(HttpHeaders.LOCATION, url).build();
}
```

`callback` 同理返回 302。

`/alert/contact/{id}/wechat/bind-url`：

```java
@GetMapping("/{id}/wechat/bind-url")
@PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:edit')")
public R<Map<String, String>> bindUrl(@PathVariable Long id,
                                      @RequestParam(defaultValue = "7") Long channelId) {
    String url = oauthService.start("/alert/contact", "bind", id, channelId);
    return R.ok(Map.of("url", url));
}
```

`/alert/contact/wechat/bind-status`、`/alert/contact/{id}/wechat/unbind` 按设计实现。

- [ ] **Step 5: 加白名单**

`SecurityConfig.WHITE_LIST` 加：

```java
"/wechat/oauth/**",
```

- [ ] **Step 6: 跑测试并提交**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=WechatBindingServiceTest test`
Expected: PASS。

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/wechat/ `
  netsight-server/src/main/java/com/netsight/config/SecurityConfig.java `
  netsight-server/src/test/java/com/netsight/modules/alert/wechat/WechatBindingServiceTest.java
git commit -m "feat(wechat): add oauth and contact binding without auto-creating contacts"
```

---

### Task 7: 登录集成

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/service/AuthService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/system/controller/AuthController.java`
- Modify: `netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceTest.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceWechatBindTest.java`

**Interfaces:**
- Consumes: `WechatBindingService.bindByTicket(tenantId, phone, ticket)`。
- Produces: `/auth/login` 可选 `wechatBindTicket`；响应 `wechatBind`。

- [ ] **Step 1: 写失败测试（绑定失败仍登录成功）**

```java
@Test
void loginSucceedsWhenBindingFails() {
    when(wechatBindingService.bindByTicket(anyLong(), anyString(), anyString()))
        .thenReturn(WechatBindResult.builder().bound(false)
            .reason("CONTACT_NOT_REGISTERED").message("请联系管理员").build());
    Map<String, Object> result = service.loginByPhone("13900000002", "123456", "m", "ticket-1");
    assertNotNull(result.get("token"));
    Map<?, ?> bind = (Map<?, ?>) result.get("wechatBind");
    assertEquals(false, bind.get("bound"));
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=AuthServiceWechatBindTest test`
Expected: 4 参方法不存在。

- [ ] **Step 3: 修改 AuthService**

- 注入 `WechatBindingService wechatBindingService`。
- 保留 3 参重载：

```java
public Map<String, Object> loginByPhone(String phone, String code, String clientType) {
    return loginByPhone(phone, code, clientType, null);
}

public Map<String, Object> loginByPhone(String phone, String code, String clientType, String wechatBindTicket) {
    // ... existing logic ...
    result.put("token", token);
    result.put("user", loginUser);
    if (StringUtils.hasText(wechatBindTicket)) {
        try {
            result.put("wechatBind", wechatBindingService.bindByTicket(
                    loginUser.getTenantId(), phone, wechatBindTicket));
        } catch (Exception e) {
            log.warn("微信绑定失败，不影响登录: {}", e.getMessage());
            result.put("wechatBind", WechatBindResult.builder().bound(false)
                    .reason("BIND_ERROR").message("微信绑定失败，请稍后重试或联系管理员。").build());
        }
    } else {
        result.put("wechatBind", WechatBindResult.builder().bound(false)
                .reason("NO_TICKET").message("").build());
    }
    return result;
}
```

- [ ] **Step 4: 修改 AuthController**

`LoginRequest` 加：

```java
/** 微信网页授权 bindTicket；为空表示普通登录 */
private String wechatBindTicket;
```

`login` 调用 4 参：

```java
return R.ok(authService.loginByPhone(request.getPhone(), request.getCode(),
        request.getClientType(), request.getWechatBindTicket()));
```

- [ ] **Step 5: 更新现有测试构造器**

`AuthServiceTest` 构造器加 `@Mock WechatBindingService`，并传入。

- [ ] **Step 6: 跑测试并提交**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=AuthServiceTest,AuthServiceWechatBindTest test`
Expected: PASS。

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/system/service/AuthService.java `
  netsight-server/src/main/java/com/netsight/modules/system/controller/AuthController.java `
  netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceTest.java `
  netsight-server/src/test/java/com/netsight/modules/system/service/AuthServiceWechatBindTest.java
git commit -m "feat(auth): bind wechat openid after phone login without blocking login"
```

---

### Task 8: 渠道元数据与模板校验

**Files:**
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/NotifyChannelService.java`
- Modify: `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationTemplateService.java`
- Test: `netsight-server/src/test/java/com/netsight/modules/alert/service/NotificationTemplateServiceTest.java`

**Interfaces:**
- Produces: wechat meta 10 项；`content` JSON 校验。

- [ ] **Step 1: 写失败测试（非法 JSON 被拒绝）**

```java
@Test
void rejectsInvalidWechatTemplateJson() {
    NotificationTemplate t = new NotificationTemplate();
    t.setChannelType("wechat");
    t.setChannelId(7L);
    t.setContent("not-json");
    assertThrows(ServiceException.class, () -> service.addTemplate(t));
}
```

- [ ] **Step 2: 修改 wechat meta**

```java
meta("wechat", "微信公众号（模板消息）", List.of(
    field("appId", "AppID", "text", true),
    field("appSecret", "AppSecret", "password", true),
    field("templateId", "默认模板 ID", "text", false),
    field("url", "点击跳转地址", "text", false,
            "默认 http://www.netsightplus.cn/m/orders"),
    field("mockMode", "模拟发送", "switch", false,
            "开启后不调用微信接口；未配置时跟随全局 WECHAT_MOCK"),
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
))
```

- [ ] **Step 3: 模板校验**

`NotificationTemplateService` 注入 `ObjectMapper`。在 `addTemplate` / `updateTemplate` 中调用：

```java
private void validateWechatContent(NotificationTemplate t) {
    if (!"wechat".equals(t.getChannelType())) return;
    if (!StringUtils.hasText(t.getContent())) {
        throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板内容不能为空");
    }
    try {
        WechatTemplatePayload.Descriptor d = objectMapper.readValue(t.getContent(), WechatTemplatePayload.Descriptor.class);
        if (d.getFields() == null || d.getFields().isEmpty()) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段不能为空");
        }
        if (d.getFields().size() > 30) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段过多");
        }
        for (WechatTemplatePayload.Field f : d.getFields()) {
            if (!StringUtils.hasText(f.getKey()) || !f.getKey().matches("^[A-Za-z][A-Za-z0-9_]*$")) {
                throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段名不合法: " + f.getKey());
            }
            if (!StringUtils.hasText(f.getValue())) {
                throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段变量不能为空: " + f.getKey());
            }
        }
    } catch (ServiceException e) {
        throw e;
    } catch (Exception e) {
        throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板内容必须是合法 JSON");
    }
}
```

- [ ] **Step 4: 跑测试并提交**

Run: `& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f netsight-server/pom.xml -Dtest=NotificationTemplateServiceTest test`
Expected: PASS。

```powershell
git add -- netsight-server/src/main/java/com/netsight/modules/alert/service/NotifyChannelService.java `
  netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationTemplateService.java `
  netsight-server/src/test/java/com/netsight/modules/alert/service/NotificationTemplateServiceTest.java
git commit -m "feat(alert): add wechat channel config and template validation"
```

---

### Task 9: 前端登录与联系人绑定

**Files:**
- Modify: `netsight-ui/src/views/m/login.vue`
- Modify: `netsight-ui/src/views/alert/contact/index.vue`
- Modify: `netsight-ui/src/api/alert/contact.js`

**Interfaces:**
- Consumes: `/wechat/oauth/start`、`/auth/login` 的 `wechatBindTicket`、`/alert/contact/{id}/wechat/bind-url`、`bind-status`、`unbind`。
- Produces: H5 登录自动绑定；联系人页二维码绑定/解绑。

- [ ] **Step 1: 登录页接入 OAuth**

`data()` 增加：

```js
wechatBindTicket: '',
wechatTip: ''
```

`created()`：

```js
if (getToken()) { this.$router.replace('/m/orders'); return; }
const query = this.$route.query;
if (query.wechatBindTicket) {
  this.wechatBindTicket = query.wechatBindTicket;
  this.wechatTip = '已获取微信身份，登录后将绑定消息接收';
}
if (query.wechat === '1' && !this.wechatBindTicket) {
  window.location.href = '/prod-api/wechat/oauth/start?redirect=/m/login';
}
```

`login()` 请求体加：

```js
wechatBindTicket: this.wechatBindTicket || undefined
```

成功后：

```js
const bind = res.data.wechatBind || {};
setToken(res.data.token);
localStorage.setItem('Admin-User', JSON.stringify(res.data.user));
if (bind.bound) {
  Toast('登录成功，微信消息已绑定');
  this.$router.replace('/m/orders');
} else {
  Toast('登录成功');
  const message = bind.message || '微信消息未绑定，可稍后在联系人页绑定。';
  this.$dialog.alert({ title: '微信绑定提示', message }).then(() => {
    this.$router.replace('/m/orders');
  });
}
```

- [ ] **Step 2: 联系人页绑定/解绑**

操作列加：

```html
<el-button v-if="!scope.row.wechatOpenid" type="text" size="mini"
  @click="handleBindWechat(scope.row)">绑定微信</el-button>
<el-button v-else type="text" size="mini"
  @click="handleUnbindWechat(scope.row)">解绑</el-button>
```

`handleBindWechat(row)`：

```js
getWechatBindUrl(row.id).then(res => {
  this.bindUrl = res.data.url;
  this.bindDialog = true;
  this.startBindPolling(row.id);
});
```

二维码用 `qrcodejs2`：

```js
import QRCode from 'qrcodejs2'
...
this.$nextTick(() => {
  this.$refs.qrcode.innerHTML = '';
  new QRCode(this.$refs.qrcode, { text: this.bindUrl, width: 220, height: 220 });
});
```

轮询：

```js
startBindPolling(id) {
  this.pollTimer = setInterval(() => {
    getWechatBindStatus(this.bindTicket).then(res => {
      if (res.data && res.data.bound) {
        clearInterval(this.pollTimer);
        this.bindDialog = false;
        this.$modal.msgSuccess('微信绑定成功');
        this.getList();
      }
    });
  }, 2000);
}
```

> `bind-status` 用 `ticket`；`bind-url` 返回值里同时返回 `ticket`，前端保存。

- [ ] **Step 3: API 封装**

`src/api/alert/contact.js` 增加：

```js
export function getWechatBindUrl(id, channelId) {
  return request({ url: `/alert/contact/${id}/wechat/bind-url`, method: 'get', params: { channelId } })
}
export function getWechatBindStatus(ticket) {
  return request({ url: '/alert/contact/wechat/bind-status', method: 'get', params: { ticket } })
}
export function unbindWechat(id) {
  return request({ url: `/alert/contact/${id}/wechat/unbind`, method: 'post' })
}
```

- [ ] **Step 4: 构建验证并提交**

Run: `& "D:\DevTools\node16\npm.cmd" --prefix netsight-ui run build:prod`
Expected: `Build complete`.

```powershell
git add -- netsight-ui/src/views/m/login.vue `
  netsight-ui/src/views/alert/contact/index.vue `
  netsight-ui/src/api/alert/contact.js
git commit -m "feat(ui): bind wechat openid on mobile login and contact page"
```

---

### Task 10: 前端模板/渠道/日志/事件配置

**Files:**
- Modify: `netsight-ui/src/views/alert/template/index.vue`
- Modify: `netsight-ui/src/views/alert/channel/index.vue`
- Modify: `netsight-ui/src/views/alert/log/index.vue`
- Modify: `netsight-ui/src/views/alert/event/index.vue`
- Modify: `netsight-ui/src/views/alert/rule/index.vue`

**Interfaces:**
- Consumes: wechat meta 10 项、`content` JSON schema、日志 `recipientResultsJson`。
- Produces: 字段映射表、逐人结果展开、8 类事件下拉。

- [ ] **Step 1: 模板页字段映射**

`data()` 增加：

```js
wechatFields: [],
wechatTemplateId: '',
wechatUrl: ''
```

`onChannelChange(id)` 中，如果 `channelType === 'wechat'`：

```js
this.wechatTemplateId = ch.config?.templateId || ''
this.wechatUrl = ch.config?.url || ''
if (!this.wechatFields.length) this.resetWechatFields()
```

`handleUpdate(row)` 解析：

```js
if (row.channelType === 'wechat') {
  try {
    const obj = JSON.parse(row.content || '{}')
    this.wechatTemplateId = obj.templateId || ''
    this.wechatUrl = obj.url || ''
    this.wechatFields = (obj.fields || []).map(f => ({
      key: f.key, value: f.value, defaultValue: f.default || ''
    }))
  } catch (e) { this.resetWechatFields() }
}
```

`submitForm()` 提交前：

```js
if (this.templateForm.channelType === 'wechat') {
  this.templateForm.content = JSON.stringify({
    templateId: this.wechatTemplateId,
    url: this.wechatUrl,
    fields: this.wechatFields.map(f => ({
      key: f.key, value: f.value, default: f.defaultValue
    }))
  })
}
```

模板 UI 用 `el-table` 编辑 `wechatFields`，按钮“添加字段”“恢复默认 5 字段”“查看原始 JSON”。

- [ ] **Step 2: 渠道页说明**

`channel/index.vue` 动态表单已支持 `switch/number/text`，只需 `NotifyChannelService` meta 返回新字段；给 `mockMode`、`oauthPublicBaseUrl` 加 hint（后端 meta 已带）。

- [ ] **Step 3: 日志页逐人结果**

`log/index.vue` 接收人列改为展开行：

```html
<el-table-column type="expand">
  <template slot-scope="scope">
    <el-table :data="parseRecipients(scope.row.recipientResultsJson)" size="mini">
      <el-table-column prop="receiver" label="接收人" />
      <el-table-column label="结果">
        <template slot-scope="s">
          <el-tag :type="s.row.success ? 'success' : (s.row.skipped ? 'info' : 'danger')">
            {{ s.row.success ? '成功' : (s.row.skipped ? '跳过' : '失败') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errcode" label="错误码" width="90" />
      <el-table-column prop="errmsg" label="错误信息" />
      <el-table-column prop="retryCount" label="重试" width="70" />
      <el-table-column prop="skipReason" label="跳过原因" />
    </el-table>
  </template>
</el-table-column>
```

结果列：

```html
<el-tag :type="scope.row.success === 1 ? 'success' : (isPartial(scope.row) ? 'warning' : 'danger')">
  {{ scope.row.success === 1 ? '成功' : (isPartial(scope.row) ? '部分成功' : '失败') }}
</el-tag>
```

`isPartial`：`recipientResultsJson` 中有成功也有失败。

- [ ] **Step 4: 事件/规则 8 类下拉**

两页 `eventTypeOptions` 改为：

```js
[
  { value: 'device_offline', label: '设备离线' },
  { value: 'device_line_abnormal', label: '链路异常' },
  { value: 'device_recovered', label: '故障恢复' },
  { value: 'device_cpu_high', label: 'CPU过高' },
  { value: 'device_mem_high', label: '内存过高' },
  { value: 'order_dispatch', label: '工单派发' },
  { value: 'stock_low', label: '库存预警' },
  { value: 'manual_notify', label: '手动通知' }
]
```

- [ ] **Step 5: 构建验证并提交**

Run: `& "D:\DevTools\node16\npm.cmd" --prefix netsight-ui run build:prod`
Expected: `Build complete`.

```powershell
git add -- netsight-ui/src/views/alert/template/index.vue `
  netsight-ui/src/views/alert/channel/index.vue `
  netsight-ui/src/views/alert/log/index.vue `
  netsight-ui/src/views/alert/event/index.vue `
  netsight-ui/src/views/alert/rule/index.vue
git commit -m "feat(ui): configure wechat template fields and show per-recipient results"
```

---

### Task 11: 数据脚本、构建与 115 验收

**Files:**
- Create: `docs/sql/wechat_mp_full_support_data.sql`
- Create: `deploy/verify_wechat_mp_115.py`（只读验收脚本）
- Modify: `netsight-server/src/main/resources/application.yml`（如需默认 `jump-url`/`oauth` 配置）

**Interfaces:**
- Produces: 模板 43 JSON、租户 7 新模板行、115 验收结果。

- [ ] **Step 1: 数据脚本**

`wechat_mp_full_support_data.sql`：

```sql
UPDATE notification_template
SET content = '{"templateId":"-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U","url":"http://www.netsightplus.cn/m/orders","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"告警"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}'
WHERE id = 43 AND tenant_id = 7;

INSERT INTO notification_template (tenant_id, template_code, template_name, channel_type, channel_id, content, enabled, create_time)
SELECT 7, 'tpl_device_line', '链路异常推送(微信公众号)', 'wechat', 7,
       '{"templateId":"-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U","fields":[{"key":"thing2","value":"{{device_name}}","default":"未知设备"},{"key":"phrase9","value":"{{event_state}}","default":"外线异常"},{"key":"character_string11","value":"{{device_no}}","default":"-"},{"key":"thing17","value":"{{location}}","default":"未登记位置"},{"key":"phrase20","value":"{{severity_text}}","default":"一般"}]}', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM notification_template WHERE tenant_id = 7 AND template_code = 'tpl_device_line' AND channel_id = 7);
-- 其余 6 条按同样模式：tpl_device_recovered / tpl_device_cpu_high /
-- tpl_device_mem_high / tpl_order_dispatch / tpl_stock_low / tpl_manual
```

> 其余 6 条必须逐条写全，不能省略；`enabled=0` 先建行，管理员按需启用。

- [ ] **Step 2: 构建**

```powershell
& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f "D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-server\pom.xml" clean package -DskipTests
& "D:\DevTools\node16\npm.cmd" --prefix "D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-ui" run build:prod
```

Expected: jar 生成、前端 `Build complete`。

- [ ] **Step 3: 115 部署前备份**

- 备份 `/opt/nams-server/netsight-server.jar`、`/opt/nams-ui/dist`、数据库 `netsight`。
- 上传新 jar/dist；重启 `/opt/nams-server/start.sh`；健康 `http://127.0.0.1:8080/actuator/health` 200。

- [ ] **Step 4: 执行 SQL**

- `wechat_mp_full_support_migration.sql`
- `wechat_mp_full_support_data.sql`

- [ ] **Step 5: 15 项验收**

按设计文档 §10.2 逐项执行；至少覆盖：

1. 健康 200；
2. 新列存在；
3. 模板 43 JSON；
4. 手动 `manual_notify` 成功；
5. 离线 43 链路不重复；
6. 同 bizId dedupe；
7. 6 条第 5/6 频控；
8. 派单 `15657477316` 成功；
9. `stock_low` 字段语义；
10. 未登记手机号登录成功且不新增联系人；
11. 管理员补录后重新登录绑定成功；
12. 解绑后发送失败明确；
13. 未关注 43004 明确；
14. 旧空 content 不回归；
15. 生产未触碰且有备份。

- [ ] **Step 6: 提交**

```powershell
git add -- docs/sql/wechat_mp_full_support_data.sql `
  deploy/verify_wechat_mp_115.py `
  netsight-server/src/main/resources/application.yml
git commit -m "chore(wechat): add 115 seed data and verification script"
```

---

## Self-Review

- **Spec coverage:** 8 类事件、模板 JSON、逐人结果、重试、去重、频控、token、OAuth、no-auto-create、日志、前端、115 验收均有对应任务。
- **Placeholder scan:** 无 TBD/TODO；Task 11 的其余 6 条模板要求逐条写全，执行时不得省略。
- **Type consistency:** `WechatBindResult`、`RecipientResult`、`SendResult` 字段在任务间一致；`WechatTemplatePayload.Descriptor.Field` 的 `defaultValue` 与 JSON `default` 需用 `@JsonProperty` 对齐。
- **Risk:** Jackson 3 注解包名需在 Task 2 编译时确认；如注解不生效，改用 `@JsonAlias` 并补测试。
