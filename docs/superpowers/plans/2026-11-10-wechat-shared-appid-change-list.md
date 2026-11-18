# 微信多租户共享服务号改造清单

**背景 Spec:** `docs/superpowers/specs/2026-11-10-wechat-mp-full-support-design.md` 第 6.11 节

**前提:** 多个租户可以绑定同一个微信服务号 `appId`，但每个租户各自建一条 `channel_type=wechat` 的 `notify_channel`；`channelId` 不共享。`notify_channel.id=7` 仍归属租户 7，不迁移、不标记全局。

**本轮范围:** 通道解析按租户校验、模板绑定校验、发送链路传租户、OAuth 去掉固定 7、登录入口透传 `channelId`。不引入 `notify_channel.scope`，不做“全局通道”，不做多 AppID 管理页面。

**完成标准:** 租户 1 能新建自己的微信通道并填共享 `appId/appSecret`；模板只能绑自己的通道；手动/自动发送只用自己的通道；OAuth 不再回落 7；租户 1 不能用通道 7；同一 openid 在两个租户各绑一条联系人时互不串扰。

---

## 一、数据与现状确认

### C1. 不新增 `scope`，确认现有表结构够用

**文件**
- `docs/sql/wechat_mp_full_support_migration.sql`
- `docs/sql/wechat_mp_full_support_data.sql`
- `netsight-server/src/main/java/com/netsight/modules/alert/entity/NotifyChannel.java`

**改法**
1. 不新增 `notify_channel.scope`，不修改 `NotifyChannel` 实体。
2. 确认现有索引：

```sql
KEY idx_tenant_type (tenant_id, channel_type)
```

3. 确认 `notify_channel` 没有 `app_id` 唯一约束；同一 `appId` 可以出现在多条通道记录中。
4. 115 数据准备不写“复制通道 7”的 SQL：
   - 租户 7 继续使用通道 7；
   - 租户 1 需要共享服务号时，通过渠道管理页新增一条 `tenant_id=1` 的微信通道，填同一个 `appId/appSecret`；
   - 不在 SQL 里直接复制通道 7，避免加密 `appSecret` 因密钥不一致无法解密。

**验收**
- `notify_channel` 表结构不变；
- 改造前 115 仍只有通道 7 一条微信通道，直到租户 1 在页面新建；
- 新建的租户 1 通道 `tenant_id=1`、`channel_type=wechat`、`enabled=1`。

**依赖:** 无。

---

## 二、通道解析

### C2. `resolveChannels` 增加 `tenantId` 校验

**文件**
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotifyChannelService.java`
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationChannelService.java`
- `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatOAuthService.java`

**改法**

1. `NotifyChannelService` 把现有的单参方法：

```java
public List<Map<String, Object>> resolveChannels(List<Long> channelIds) {
    // selectById，未校验租户
}
```

改为双参：

```java
public List<Map<String, Object>> resolveChannels(List<Long> channelIds, Long tenantId) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (channelIds == null || channelIds.isEmpty()) {
        return result;
    }
    for (Long channelId : channelIds) {
        if (channelId == null) {
            continue;
        }
        NotifyChannel c = channelMapper.selectById(channelId);
        if (c == null) {
            throw new ServiceException("通道不存在或不属于当前租户，拒绝使用");
        }
        if (c.getEnabled() == null || c.getEnabled() == 0) {
            continue;
        }
        if (tenantId == null || !tenantId.equals(c.getTenantId())) {
            throw new ServiceException("通道不属于当前租户，拒绝使用");
        }
        Map<String, Object> config = parseConfig(c.getConfigJson());
        decryptConfig(config);
        Map<String, Object> item = new HashMap<>();
        item.put("channelId", c.getId());
        item.put("tenantId", c.getTenantId());
        item.put("channelType", c.getChannelType());
        item.put("channelName", c.getChannelName());
        item.put("config", config);
        result.add(item);
    }
    return result;
}
```

2. 删除单参方法，避免后续再出现“无租户解析”。
3. 所有调用点改为双参：
   - `NotificationChannelService.resolveChannelInstance`：`resolveChannels(List.of(tpl.getChannelId()), tenantId)`；
   - `NotificationChannelService.dispatchByChannelIds`：`resolveChannels(channelIds, event.getTenantId())`；
   - 短信降级分支第二次解析同样传 `event.getTenantId()`；
   - `WechatOAuthService` 不再调用 `resolveChannels`，改用 C5 的 `requireOAuthChannel`。

**验收**
- `resolveChannels(List.of(7L), 1L)` 抛“通道不属于当前租户”；
- `resolveChannels(List.of(租户1通道id), 1L)` 正常返回；
- 异步自动发送不传租户时也必须抛错，不能静默取到通道 7；
- 租户 7 原链路不回归。

**依赖:** 无。

---

## 三、模板绑定校验

### C3. 模板保存/修改时校验通道属于本租户

**文件**
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationTemplateService.java`
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotifyChannelService.java`

**改法**

**前置:** `NotificationTemplateService` 增加构造注入字段（该类已使用 `@RequiredArgsConstructor`）：

```java
private final NotifyChannelService notifyChannelService;
```

1. `NotifyChannelService` 新增：

```java
public void assertChannelUsable(Long channelId, Long tenantId) {
    if (channelId == null) {
        throw new ServiceException("请选择通知通道");
    }
    NotifyChannel c = channelMapper.selectById(channelId);
    if (c == null || c.getEnabled() == null || c.getEnabled() == 0) {
        throw new ServiceException("绑定的通道不存在、未启用或不属于当前租户");
    }
    if (tenantId == null || !tenantId.equals(c.getTenantId())) {
        throw new ServiceException("不能绑定其他租户的通道");
    }
}
```

2. `NotificationTemplateService.addTemplate` 在 `tenantId` 归一化之后、重复校验之前增加：

```java
notifyChannelService.assertChannelUsable(template.getChannelId(), template.getTenantId());
```

3. `updateTemplate` 在 `channelId` 非空校验后，先锁定模板现有租户，再校验（避免超管编辑时误用当前租户，也避免借更新改 `tenantId`）：

```java
template.setTenantId(exist.getTenantId());
notifyChannelService.assertChannelUsable(template.getChannelId(), exist.getTenantId());
```

4. 不改变原有 `(templateCode, tenantId, channelId)` 去重逻辑。

**验收**
- 租户 1 建模板时传通道 7，返回“不存在、未启用或不属于当前租户”；
- 租户 1 建模板时传自己的通道 id，成功；
- 通道停用时保存失败；
- 原有模板 43 不受影响。

**依赖:** C1。

---

## 四、发送链路

### C4. 手动/自动发送统一传租户

**文件**
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotificationChannelService.java`

**改法**

1. `resolveChannelInstance(NotificationTemplate tpl, Long tenantId)` 第 157 行改为：

```java
List<Map<String, Object>> resolved =
        notifyChannelService.resolveChannels(List.of(tpl.getChannelId()), tenantId);
```

2. `dispatchByChannelIds(...)` 第 231 行改为：

```java
List<Map<String, Object>> channels =
        notifyChannelService.resolveChannels(channelIds, event.getTenantId());
```

3. 短信降级分支第 254 行改为：

```java
List<Map<String, Object>> wxChannels =
        notifyChannelService.resolveChannels(channelIds, event.getTenantId()).stream()
                ...
```

4. 不改 `sendOne` / `sendOneResolved` 的发送语义，只保证解析阶段拿到的通道属于事件租户。
5. 顺手修正 `dispatchByTemplateGroup` 第 111 行注释：当前实现没有“按类型自动选默认实例”，未绑定通道时直接跳过并记录 warning。

**验收**
- 租户 1 手动发送选择自己的通道，日志 `tenant_id=1`、`channel_id=租户1通道id`；
- 租户 1 自动发送使用模板绑定的自己的通道；
- 模板错绑通道 7 时发送失败并给出明确错误，不静默发给租户 7 联系人；
- 租户 7 原链路不回归。

**依赖:** C2。

---

## 五、OAuth 默认通道

### C5. 删除 `7L` / `defaultValue="7"`

**文件**
- `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatOAuthService.java`
- `netsight-server/src/main/java/com/netsight/modules/alert/wechat/WechatOAuthController.java`
- `netsight-server/src/main/java/com/netsight/modules/alert/service/NotifyChannelService.java`
- `netsight-server/src/main/resources/application.yml`

**改法**

**前置:** `WechatOAuthController` 增加构造注入（并补 import `com.netsight.modules.alert.service.NotifyChannelService`）：

```java
private final NotifyChannelService notifyChannelService;
```

1. `application.yml` 的 `wechat` 段新增：

```yaml
        # OAuth 默认微信通道 ID；为空时按 channel_type=wechat + enabled=1 解析
        oauth-channel-id: ${WECHAT_OAUTH_CHANNEL_ID:}
```

2. `NotifyChannelService` 增加配置字段：

```java
@Value("${netsight.alert.channel.wechat.oauth-channel-id:}")
private String configuredOAuthChannelId;
```

并补 import `org.springframework.beans.factory.annotation.Value`（`NotifyChannelService` 当前 import 列表没有它）。

3. `NotifyChannelService` 新增 OAuth 通道解析：

```java
public Map<String, Object> requireOAuthChannel(Long channelId, Long tenantId) {
    NotifyChannel c;
    if (channelId != null) {
        c = channelMapper.selectById(channelId);
        if (c == null) {
            throw new ServiceException("微信公众号通道不存在");
        }
        return toOAuthChannel(c, tenantId);
    }
    if (configuredOAuthChannelId != null && !configuredOAuthChannelId.isBlank()) {
        Long configuredId;
        try {
            configuredId = Long.valueOf(configuredOAuthChannelId.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("配置的微信 OAuth 通道 ID 非法");
        }
        c = channelMapper.selectById(configuredId);
        if (c == null) {
            throw new ServiceException("配置的微信 OAuth 通道不存在");
        }
        return toOAuthChannel(c, tenantId);
    }
    List<NotifyChannel> candidates = channelMapper.selectList(
            new LambdaQueryWrapper<NotifyChannel>()
                    .eq(NotifyChannel::getChannelType, "wechat")
                    .eq(NotifyChannel::getEnabled, 1)
                    .eq(tenantId != null, NotifyChannel::getTenantId, tenantId)
                    .orderByAsc(NotifyChannel::getId));
    if (candidates.isEmpty()) {
        throw new ServiceException("未配置微信 OAuth 通道，请管理员先配置");
    }
    String appId = appIdOf(candidates.get(0));
    boolean sameAppId = candidates.stream().allMatch(x -> appId.equals(appIdOf(x)));
    if (!sameAppId) {
        throw new ServiceException("存在多个微信服务号，请显式指定 OAuth 通道 ID");
    }
    return toOAuthChannel(candidates.get(0), tenantId);
}

private String appIdOf(NotifyChannel c) {
    Map<String, Object> cfg = parseConfig(c.getConfigJson());
    decryptConfig(cfg);
    return Objects.toString(cfg.get("appId"), "");
}

private Map<String, Object> toOAuthChannel(NotifyChannel c, Long tenantId) {
    if (!"wechat".equals(c.getChannelType())) {
        throw new ServiceException("通道类型不是微信公众号");
    }
    if (c.getEnabled() == null || c.getEnabled() == 0) {
        throw new ServiceException("微信公众号通道未启用");
    }
    if (tenantId != null && !tenantId.equals(c.getTenantId())) {
        throw new ServiceException("微信公众号通道不属于当前租户");
    }
    Map<String, Object> cfg = parseConfig(c.getConfigJson());
    decryptConfig(cfg);
    String appId = Objects.toString(cfg.get("appId"), "");
    String appSecret = Objects.toString(cfg.get("appSecret"), "");
    if (appId.isBlank() || appSecret.isBlank()) {
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
```

4. `start()` 删除：

```java
Long chId = channelId == null ? 7L : channelId;
```

改为：

```java
Map<String, Object> ch = notifyChannelService.requireOAuthChannel(channelId, tenantId);
Long chId = longOf(ch.get("channelId"));
```

5. `start()` 的 state 只写传入的租户：

```java
stateData.put("tenantId", tenantId);
```

删除 `tenantId != null ? tenantId : ch.get("tenantId")`，不再用通道的 `tenant_id` 填充 state。

6. `callback()` 用 state 里的 `channelId` 和 `tenantId` 解析，配置错误必须转成 `CHANNEL_CONFIG`：

```java
Map<String, Object> ch;
try {
    ch = notifyChannelService.requireOAuthChannel(
            longOf(state.get("channelId")), longOf(state.get("tenantId")));
} catch (ServiceException e) {
    return errorRedirect(mode, "CHANNEL_CONFIG", e.getMessage());
}
Map<String, Object> cfg = configOf(ch);
```

同时删除 `WechatOAuthService` 中不再使用的 `private Map<String, Object> resolveWechatChannel(Long channelId)` 方法，并删除随之无用的 `java.util.List` import。

7. `WechatOAuthController.bindUrl` 删除 `defaultValue = "7"`：

```java
public R<Map<String, String>> bindUrl(@PathVariable Long id,
                                      @RequestParam(required = false) Long channelId) {
```

8. `bindUrl` 在未传 `channelId` 时按联系人租户解析：

```java
Long chId = channelId;
if (chId == null) {
    chId = notifyChannelService.requireTenantWechatChannelId(contact.getTenantId());
}
WechatOAuthService.StartResult result = oauthService.start(
        "/alert/contact", "bind", id, chId, contact.getTenantId());
return R.ok(Map.of("url", result.getUrl(), "ticket", result.getTicket() == null ? "" : result.getTicket()));
```

9. `NotifyChannelService` 新增：

```java
public Long requireTenantWechatChannelId(Long tenantId) {
    if (tenantId == null) {
        throw new ServiceException("联系人缺少租户");
    }
    List<NotifyChannel> list = channelMapper.selectList(
            new LambdaQueryWrapper<NotifyChannel>()
                    .eq(NotifyChannel::getTenantId, tenantId)
                    .eq(NotifyChannel::getChannelType, "wechat")
                    .eq(NotifyChannel::getEnabled, 1)
                    .orderByAsc(NotifyChannel::getId));
    if (list.isEmpty()) {
        throw new ServiceException("该租户未配置启用的微信通道");
    }
    if (list.size() > 1) {
        String appId = appIdOf(list.get(0));
        boolean sameAppId = list.stream().allMatch(x -> appId.equals(appIdOf(x)));
        if (!sameAppId) {
            throw new ServiceException("该租户存在多个微信服务号，请指定 channelId");
        }
    }
    return list.get(0).getId();
}
```

**验收**
- 登录模式不传 `channelId`、配置 `WECHAT_OAUTH_CHANNEL_ID=7`：租户共用同一个服务号时正常生成授权 URL；
- 不传、未配置、只有 1 个不同 appId 的启用微信通道：正常；
- 未配置、存在多个不同 appId：`start` 抛配置错误，`callback` 返回 `CHANNEL_CONFIG`；
- 同一 appId 有多条通道：取 id 最小的一条，不报错；
- 登录模式 state 的 `tenantId` 为 null；
- 管理端绑定租户 1 联系人，未传 channelId 时解析到租户 1 的通道；传通道 7 被拒绝。

**依赖:** C2、C3。

---

## 六、前端登录入口

### C6. `login.vue` 透传 `channelId`

**文件**
- `netsight-ui/src/views/m/login.vue`

**改法**

1. `created()` 检测 `wechat=1` 时，读取路由 query 的 `channelId`：

```js
const channelId = this.$route.query.channelId
let url = '/prod-api/wechat/oauth/start?redirect=/m/login'
if (channelId) {
  url += '&channelId=' + encodeURIComponent(channelId)
}
window.location.replace(url)
```

2. 不带 `channelId` 时保持原行为，使用默认 OAuth 通道。
3. `contact/index.vue` 的绑定二维码不需要前端传 `channelId`；后端 `bindUrl` 按联系人租户解析。若该租户有多个不同服务号，后端返回“请指定 channelId”，由管理员先在渠道页只保留一个启用服务号（同一 appId 多条通道取最小 id）。

**验收**
- `m/login?wechat=1` 跳转不带 `channelId`；
- `m/login?wechat=1&channelId=20` 跳转带 `channelId=20`；
- 专属入口取到通道 20 的 appId，公共入口取默认共享服务号。

**依赖:** C5。

---

## 七、脚本、测试与验收

### C7. 验收脚本与测试

**文件**
- `deploy/verify_wechat_mp_115.py`
- `netsight-server/src/test/java/com/netsight/modules/alert/service/NotifyChannelServiceTest.java`（新建或补充）
- `netsight-server/src/test/java/com/netsight/modules/alert/service/NotificationTemplateServiceTest.java`（补充）
- `netsight-server/src/test/java/com/netsight/modules/alert/wechat/WechatOAuthServiceTest.java`（新建或补充）

**改法**

1. `verify_wechat_mp_115.py` 增加只读检查：
   - `notify_channel.id=7` 仍为 `tenant_id=7`、`channel_type=wechat`、`enabled=1`；
   - 不存在 `scope` 列（确认没有误加）；
   - 租户 1 通道存在时，`tenant_id=1`、`channel_type=wechat`；
   - 打印启用微信通道的 `id/tenant_id/appId`（appId 可脱敏，不打印 appSecret）。

2. 单元测试用例：
   - `resolveChannels(List.of(7L), 1L)` 抛跨租户错误；
   - `resolveChannels(List.of(租户1通道id), 1L)` 成功；
   - `assertChannelUsable(7L, 1L)` 抛跨租户错误；
   - `assertChannelUsable(租户1通道id, 1L)` 成功；
   - `requireOAuthChannel(7L, null)` 成功（登录模式）；
   - `requireOAuthChannel(7L, 1L)` 失败（管理端绑定租户 1 时用了租户 7 通道）；
   - `requireOAuthChannel(null, null)` 多 appId 时抛配置错误（callback 映射 `CHANNEL_CONFIG`）；
   - 同一 appId 多条通道时取最小 id；
   - `requireTenantWechatChannelId(1L)` 返回租户 1 通道 id；多个不同 appId 时报错，同一 appId 多条取最小 id。

3. 115 端到端验收按设计文档 10.2 的 21 项执行，重点新增：
   - 租户 1 渠道页新建共享 appId 的通道，列表看不到通道 7；
   - 租户 1 模板只能绑自己的通道，API 传 7 被拒绝；
   - 租户 1 手动发送日志 `tenant_id=1`、`channel_id=租户1通道id`；
   - 租户 1 自动发送只发租户 1 联系人；
   - 同一 openid 在租户 1/7 各绑一条，互不串扰；
   - 多 appId 时 OAuth 返回 `CHANNEL_CONFIG`；同一 appId 多条不报错。

**验收**
- `mvn test` 全绿；
- `verify_wechat_mp_115.py` 新增项 PASS；
- 不触碰生产 `1.94.26.126`。

**依赖:** C1-C6。

---

## 八、实施顺序

1. C1 确认表结构，不写 `scope`；
2. C2 `resolveChannels` 加 `tenantId`，改所有调用点；
3. C3 模板保存/修改校验通道归属；
4. C4 发送链路统一传租户；
5. C5 OAuth 去 7、加配置、bindUrl 按租户解析；
6. C6 登录页透传 `channelId`；
7. C7 脚本、单测、115 验收。

> C2 必须先于 C3/C4/C5，否则模板校验和发送链路没有统一的租户解析入口；C5 依赖 C2 的 `selectById` 行为。

---

## 九、明确不做

- 不新增 `notify_channel.scope`；
- 不做“所有租户共用同一条 channel 7”的全局通道模型；
- 不做 `selectByIdIgnoreTenant` / `selectGlobalByType` / `selectVisiblePage`；
- 不做多 AppID 管理页面；
- 不做“一个微信号只能绑一个租户”的全局唯一约束；
- 不做登录页租户选择；手机号跨租户仍明确报错；
- 不碰生产 `1.94.26.126`；
- 不改微信发送器内部逐人重试/去重/频控逻辑。

---

## 十、回滚

- 代码回滚到 `0656207`；
- 不涉及 `notify_channel` 表结构回滚，因为没有新增列；
- 删除 `WECHAT_OAUTH_CHANNEL_ID` 配置即可恢复旧行为；
- 租户 1 新建的通道可在页面停用或删除，不影响通道 7；
- 115 恢复 jar/dist/DB 备份；
- 前端回滚 dist 备份。
