# 服务号消息推送全面支持与首次登录 openid 绑定设计

**日期**：2026-11-10  
**分支**：`feature/wechat-mp-full-support`  
**基线提交**：`bfdcf47`（含 AI 诊断 P0 与 H5 问诊输入限制）  
**状态**：设计定稿，待实现计划  
**范围**：NetSight 测试环境 `115.159.194.149`；生产 `1.94.26.126` 本轮不触碰

---

## 1. 背景与现状

当前服务号“能收到”只证明最短链路已通，不等于“全面支持”。

已核实的代码与数据事实：

- 唯一启用的微信模板是 `notification_template.id=43`，`tenant_id=7`、`template_code=tpl_device_offline`、`channel_id=7`、`enabled=1`，`content` 为空字符串。
- `WechatMpChannelSender` 硬编码 5 个字段：`thing2/phrase9/character_string11/thing17/phrase20`，换模板即失效。
- 租户 7 的离线规则 `templateId=11` 实际是 `aliyun_sms` 模板（`channel_id=4`），但 `templateCode=tpl_device_offline` 与微信模板 43 同组，模板组把 43 一起带出，所以离线能收到。这是“借道短信模板 code”的巧合链路，不是通用能力。
- 全库仅 `notify_contact.id=3`（孙扬，`15657477316`）绑定了 openid；租户 1 所有微信模板 `channel_id=NULL`、`enabled=0`，租户 1 当前完全没有微信触达。
- `WorkOrderNotifier` 构造联系人时只 set `name/mobile`，没有 `wechatOpenid`，派单微信必然失败。
- `NotificationChannelService.sendWithRetry` 首发送失败后对整批 `receivers` 重试，会重复推送已成功的人。
- `SendResult` 只有 `success/errorMsg/thirdPartyMsgId/costTime`，没有逐人结果、是否可重试、实际模板 ID。
- `notification_log` 没有 `channel_id/template_id/recipient_results_json`，无法审计逐人投递。
- `NotificationTemplate.content` 对 `channelType=wechat` 完全无效。
- `NotifyChannelService` 的 wechat meta 只有 `appId/appSecret/templateId/url` 四项，没有 mock/频控/去重/超时/OAuth 基址。
- H5 登录 `m/login.vue` 是“手机号 + 短信验证码”；`AuthService.loginByPhone` 识别 `sys_user`、`repairer`、`tenantId` 后签发 JWT，没有 openid 入参，`LoginUser` 也没有 openid 字段。
- `SecurityConfig.WHITE_LIST` 不含任何微信 OAuth 回调路径。
- 115 的 `wechat-test.conf` 只有 `api.netsightplus.cn` 的 443 静态站和 `/wx-sign/` 反代，没有 `/prod-api/`；`www.netsightplus.cn` 的 80 端口 `nams.conf` 有 `/prod-api/` 反代到 `127.0.0.1:8080`。
- `www.netsightplus.cn` 的 HTTPS 证书链不可信；`api.netsightplus.cn` 有有效证书和微信校验文件。

因此本轮不是“再补一个 sender”，而是补四类能力：

1. 模板字段可配置；
2. openid 绑定入口可用；
3. 逐人投递可靠（逐人结果、重试不重复、频控、去重）；
4. 配置与审计闭环。

---

## 2. 目标与非目标

### 2.1 目标

- 覆盖代码现有 8 类事件：`device_offline`、`device_line_abnormal`、`device_recovered`、`device_cpu_high`、`device_mem_high`、`manual_notify`、`order_dispatch`、`stock_low`。
- `NotificationTemplate.content` 对微信通道存 JSON 模板描述符，后台可配模板 ID、跳转 URL、字段映射，换模板不改代码。
- `notify_contact` 支持 openid 绑定、解绑、扫码绑定；首次公众号 H5 登录时静默获取 openid 并绑定已登记联系人。
- 微信发送逐人独立、逐人结果、逐人重试、发送去重、频控、token 缓存。
- 日志可看到每个接收人的成功/失败/跳过、微信错误码、重试次数、实际模板 ID。
- 115 测试环境完成端到端验证，现有设备离线链路不回归。

### 2.2 非目标

- 不新建业务事件类型；只覆盖代码现有 8 类。
- 不把系统用户/维修员档案直接当消息接收人；消息接收人仍是 `notify_contact`。
- 不代用户在公众号后台创建模板；模板 ID 是微信侧固定资源，只能配置。
- 不做告警风暴聚合、不做微信回执/关注事件回调、不做模板消息以外的微信能力。
- 不改生产 `1.94.26.126`。
- 不把 openid 当唯一登录凭证；首次登录仍用手机号+短信验证码确认身份。
- 不做多公众号 openid 统一；本期只支持当前一个服务号 `wx38b4e7eb23e5e249`，但存储绑定时的 `appId` 以便未来扩展。

---

## 3. 总体架构

```
事件/告警/工单/备件
      │
      ▼
NotificationRule（按 eventType 匹配）
      │ templateId
      ▼
NotificationTemplate（templateCode 模板组）
      │ content = 微信模板描述符 JSON
      ▼
NotificationChannelService（模板组分发 / 手动分发）
      │ SendRequest（channelId + content + vars + receivers）
      ▼
WechatMpChannelSender（通用）
      │ ① 解析描述符 ② 渲染字段 ③ 逐 openid 发送
      │ ④ 逐人重试 ⑤ 去重 ⑥ 频控 ⑦ token 缓存
      ▼
notification_log（逐人结果 + 微信模板 ID + 重试次数）
```

核心原则：

- 模板描述符是唯一“换模板”的扩展点；sender 不认识业务事件。
- 发送器只依赖 `SendRequest`/`SendResult`，不修改这两个共用模型的既有语义；只加可选字段。
- 逐人结果是一等公民；整批重试被禁用，避免重复推送。
- 绑定与登录解耦：登录必须可用，绑定失败不阻断登录。

---

## 4. 模板描述符

### 4.1 JSON Schema

`NotificationTemplate.content` 对 `channelType=wechat` 存：

```json
{
  "templateId": "-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U",
  "url": "http://www.netsightplus.cn/m/orders",
  "fields": [
    { "key": "thing2", "value": "{{device_name}}", "default": "未知设备" },
    { "key": "phrase9", "value": "{{event_state}}", "default": "告警" },
    { "key": "character_string11", "value": "{{device_no}}", "default": "-" },
    { "key": "thing17", "value": "{{location}}", "default": "未登记位置" },
    { "key": "phrase20", "value": "{{severity_text}}", "default": "一般" }
  ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `templateId` | 否 | 微信模板 ID；为空回落到渠道实例 `templateId` |
| `url` | 否 | 点击跳转 URL；为空回落到 `extra.wechatUrl` → 渠道 `url` → 全局默认 |
| `fields` | 是 | 字段数组；每项 `key/value/default` |
| `key` | 是 | 微信模板字段名，如 `thing2`、`phrase9`、`character_string11` |
| `value` | 是 | 支持 `{{变量}}`；渲染后为空用 `default` |
| `default` | 否 | 兜底值；仍为空时按字段类型给空串 |

兼容规则：

- `content` 为空、纯文本、或不是合法 JSON → 走内置默认 5 字段映射，模板 ID取渠道 `templateId`。这保证 115 现有离线不回归。
- 非 wechat 通道仍按原逻辑把 `content` 当文本模板渲染，不解析 JSON。

### 4.2 字段截断

按微信硬限制截断，防 `47003`：

| 字段前缀 | 上限 |
|---|---|
| `thing` / `const` / `first` / `remark` | 20 |
| `phrase` / `letter` / `symbol` | 5 |
| `character_string` / `number` | 32 |
| `amount` | 10 |
| `date` | 10 |
| `time` | 20 |
| 未知前缀 | 20 |

截断按 Unicode 字符数，不按字节；超长直接截断，不报错。

### 4.3 内置派生变量

sender 在渲染前补齐：

| 变量 | 来源 |
|---|---|
| `event_state` | `eventType` 映射，如 `设备离线`、`CPU过高` |
| `event_title` | `eventType` 映射，如 `设备离线告警`、`工单派发` |
| `severity_text` | `severity` 映射：`info→提示`、`warning→警告`、`critical→严重`、`major→重要`、`minor→次要` |
| `device_no` | `deviceCode`，为空用 `deviceIp`，再为空用 `bizId` |
| `content` | `SendRequest.content` 或 `vars.content` |
| `biz_id` | 事件业务 ID |
| `time` | `yyyy-MM-dd HH:mm:ss` |
| `tenant_name` | `vars.tenant_name` |

事件文案映射：

| eventType | event_state | event_title |
|---|---|---|
| `device_offline` | 设备离线 | 设备离线告警 |
| `device_line_abnormal` | 外线异常 | 链路异常告警 |
| `device_recovered` | 已恢复 | 故障恢复通知 |
| `device_cpu_high` | CPU过高 | CPU使用率告警 |
| `device_mem_high` | 内存过高 | 内存使用率告警 |
| `order_dispatch` | 待接单 | 工单派发 |
| `stock_low` | 库存不足 | 备件库存预警 |
| `manual_notify` | 通知 | 系统通知 |

未知 eventType：`event_state=告警`、`event_title=系统通知`。

### 4.4 模板校验

`NotificationTemplateService.addTemplate/updateTemplate`：

- 非 wechat：`content` 必填，保持原逻辑。
- wechat：`content` 必须是合法 JSON；`templateId` 可空；`fields` 非空；字段名匹配 `^[A-Za-z][A-Za-z0-9_]*$`；`value` 非空；字段数不超过 30；JSON 长度不超过 8KB。
- 校验失败返回明确错误，不写库。

---

## 5. 发送器改造

### 5.1 拆类

| 类 | 职责 |
|---|---|
| `WechatTemplatePayload` | 解析 JSON、取字段列表、渲染、截断、默认映射、事件文案映射 |
| `WechatTokenProvider` | Redis 优先 + 本地兜底获取 `stable_token`；40001/40014/42001 强制刷新重试一次 |
| `WechatMpChannelSender` | 编排：解析接收人、逐人发送、逐人重试、去重、频控、组装 `SendResult` |
| `WechatApiClient`（可注入） | HTTP POST JSON，便于单测替换 |

`WechatMpChannelSender` 不再直接持有 `static` token Map，token 交给 `WechatTokenProvider`。

### 5.2 `SendRequest` 扩展

新增可选字段：

```java
private Long channelId;
```

既有字段语义不变；`content` 对 wechat 为模板描述符 JSON，对其他通道为已渲染内容。类注释同步说明。

### 5.3 `SendResult` 扩展

新增：

```java
private boolean retryable = true;
private String templateId;
private String renderedContent;
private List<RecipientResult> recipientResults;
```

`RecipientResult`：

```java
private String receiver;      // 脱敏 openid
private boolean success;
private Integer errcode;
private String errmsg;
private int retryCount;
private boolean skipped;
private String skipReason;    // duplicate / rate_limit / app_id_mismatch / unbound
```

既有 `success/errorMsg/thirdPartyMsgId/costTime` 保留。

### 5.4 逐人发送

- 每个 openid 独立调用 `message/template/send`。
- 可重试错误：网络异常、HTTP 5xx、`40001/40014/42001`、`-1`。
- 不可重试错误：`40003`（invalid openid）、`43004`（未关注）、`47003`（字段超限）、`40164`（IP 白名单）、`45009/45026`（频控）。
- 单人最多 3 次尝试（首次 + 2 次重试）；间隔取渠道 `retryIntervals`，默认 `1000,2000`；总耗时受 `timeoutMs` 控制。
- 每次重试后更新该人的 `retryCount`。
- 部分成功：`success=false`，`recipientResults` 保留全部明细；事件状态 `part_failed`。

### 5.5 禁止整批重试

`NotificationChannelService.sendWithRetry` 改为：

- 首发送失败后，仅当 `result.isRetryable()` 且 `result.getRecipientResults() == null` 才整批重试。
- wechat 的 `recipientResults` 非空，因此通道层不再整批重发；重试已在 sender 内逐人完成。

这修掉“已经成功的人重复收到”的缺陷。

### 5.6 发送去重

- key：`netsight:notify:dedupe:{tenantId}:{channelId}:{bizId}:{templateId}:{openidHash}`。
- `openidHash` 用 SHA-256 前 16 位十六进制，避免 Redis key 含完整 openid。
- 发送前 `setIfAbsent(key, "LOCK", 60s)` 抢锁；成功后改写为 `"OK"`，TTL `dedupTtlHours`（默认 24h）；失败删除锁。
- 抢锁失败：该 openid 标记 `skipped/duplicate`，不发送。
- `bizId` 为空（手动补发）不做去重；管理员可主动重发。
- 全部因重复跳过：`success=true`，`errorMsg="全部接收人已发送过，跳过"`；日志逐人显示 `duplicate`。

### 5.7 频控

- key：`netsight:wechat:rate:{appId}:{openid}:{yyyyMMddHHmm}`。
- 固定窗口，默认 `rateLimitPerMinute=4`，渠道可配；`INCR` 后首次设置 60s TTL。
- 超限：该 openid 标记 `skipped/rate_limit`，不重试。
- 全部超限：`success=false`，`errorMsg="触发微信频控，未送达"`，`retryable=false`。

### 5.8 Token

- Redis key：`netsight:wechat:token:{appId}`，TTL 取微信返回 `expires_in` 减 300 秒。
- Redis 不可用：进程内 `ConcurrentHashMap` 兜底。
- 获取失败或微信返回 `40001/40014/42001`：`force_refresh=true` 重试一次；仍失败则本次发送失败，`retryable=true`。
- 日志不打印 token，只打印 appId 后 4 位。

### 5.9 mockMode

- 实例 `config_json.mockMode` 优先；未配置时用全局 `netsight.alert.channel.wechat.mock-mode`。
- sender 启动/每次发送日志打印 `effectiveMock` 和来源（instance/global）。
- mock 模式返回成功，不调微信接口；`thirdPartyMsgId="MOCK"`，`recipientResults` 标记 `success=true`。

### 5.10 跳转 URL

优先级：

1. 模板描述符 `url`
2. `SendRequest.extra.wechatUrl`
3. 渠道 `config_json.url`
4. 全局 `netsight.alert.channel.wechat.jump-url`，默认 `http://www.netsightplus.cn/m/orders`

URL 必须以 `http://` 或 `https://` 开头；否则回落默认。

---

## 6. openid 绑定与首次登录

### 6.1 硬约束

**首次登录时，手机号查不到 `notify_contact`，不自动创建联系人。**

只允许绑定已登记且启用的联系人：

- `tenantId + phone` 无记录 → `CONTACT_NOT_REGISTERED`，提示联系管理员；
- 记录存在但 `status != 1` → `CONTACT_DISABLED`；
- 同手机号多条启用记录 → `CONTACT_AMBIGUOUS`；
- 唯一启用记录 → 继续 openid 冲突校验；
- openid 已绑定同租户其他联系人 → `OPENID_BOUND_OTHER`；
- 该联系人已绑定其他 openid → `CONTACT_BOUND_OTHER`，默认不覆盖；
- 同一联系人同一 openid 重复绑定 → `BOUND`（幂等成功）；
- 全部通过 → `BOUND`。

登录与绑定解耦：**绑定失败不影响登录成功，不阻断进入 H5 工单列表。**

### 6.2 首次登录时序

```
公众号菜单
  → http://www.netsightplus.cn/m/login?wechat=1
  → 前端发现 wechat=1 且无 token
  → 跳转 /prod-api/wechat/oauth/start?redirect=/m/login
  → 后端生成一次性 state，302 到微信网页授权
  → 微信静默授权（snsapi_base）
  → 回调 /prod-api/wechat/oauth/callback?code=...&state=...
  → 后端 code 换 openid（sns/oauth2/access_token）
  → 生成 bindTicket，Redis 10 分钟
  → 302 回 /m/login?wechatBindTicket=...
  → 用户输入手机号+验证码
  → /auth/login 带 wechatBindTicket
  → AuthService 校验验证码、识别 sys_user/repairer/tenantId
  → WechatBindingService 按 tenantId+phone 查 notify_contact
  → 唯一启用联系人：写 wechat_openid / wechat_app_id / wechat_bound_at
  → 无联系人：不插入，返回 CONTACT_NOT_REGISTERED
  → 登录成功，返回 token + wechatBind 结果
```

### 6.3 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/wechat/oauth/start` | 否 | 参数 `redirect`；生成 state，302 微信授权 |
| GET | `/wechat/oauth/callback` | 否 | 微信回调；code 换 openid；生成 bindTicket；302 登录页 |
| GET | `/alert/contact/{id}/wechat/bind-url` | 是 | 生成联系人二维码绑定 URL，返回 `{url, ticket}` |
| GET | `/alert/contact/wechat/bind-status` | 是 | 参数 `ticket`；PC 轮询绑定结果 |
| POST | `/alert/contact/{id}/wechat/unbind` | 是 | 解绑，清空 openid/appId/boundAt |
| POST | `/auth/login` | 否 | 增加可选 `wechatBindTicket` |

`/wechat/oauth/**` 加入 `SecurityConfig.WHITE_LIST`，因为微信回调没有登录态。

### 6.4 state 与 ticket

- `state`：随机 32 位十六进制；Redis `netsight:wechat:oauth:state:{state}` → `{mode, redirect, contactId, tenantId, channelId}`，TTL 10 分钟，一次性。
- `bindTicket`：随机 32 位十六进制；Redis `netsight:wechat:bind:ticket:{ticket}` → `{openid, appId, unionid, channelId}`，TTL 10 分钟。
- `bind-status`：Redis `netsight:wechat:bind:status:{ticket}` → `BOUND/FAIL_REASON`，TTL 5 分钟。
- `redirect` 必须通过白名单校验：只允许 `/m/login`、`/m/orders`、`/m/order-detail`、`/m/out-requests`、`/alert/contact`；禁止外站 URL。
- 绑定成功后删除 ticket；失败也删除 ticket（一次性），提示用户重新从公众号进入或让管理员发绑定二维码。

### 6.5 OAuth 参数

授权 URL：

```
https://open.weixin.qq.com/connect/oauth2/authorize
  ?appid={appId}
  &redirect_uri={URLEncoder.encode(callback)}
  &response_type=code
  &scope=snsapi_base
  &state={state}#wechat_redirect
```

换取 openid：

```
GET https://api.weixin.qq.com/sns/oauth2/access_token
  ?appid={appId}
  &secret={appSecret}
  &code={code}
  &grant_type=authorization_code
```

返回 `openid`、`access_token`、`expires_in`、`refresh_token`、`scope`，有 unionid 时一并返回。

注意：

- 网页授权 `access_token` 只用于网页授权，不能用于模板消息；模板消息仍用 `cgi-bin/stable_token`。
- `code` 5 分钟有效、一次性；`state` 必须校验。
- AppSecret 只在后端使用，绝不出现在前端 URL/日志。

### 6.6 绑定结果返回

`/auth/login` 响应增加：

```json
{
  "token": "...",
  "user": { ... },
  "wechatBind": {
    "bound": false,
    "reason": "CONTACT_NOT_REGISTERED",
    "message": "当前手机号未登记为通知联系人，请联系管理员在【告警中心 → 通知联系人】中添加并启用后，再重新从公众号进入绑定。"
  }
}
```

`bound=true` 时 `reason/message` 为空。

### 6.7 前端

`m/login.vue`：

- `created()`：有 token 直接跳 `/m/orders`；无 token 且 query `wechat=1`，`window.location.href = '/prod-api/wechat/oauth/start?redirect=/m/login'`。
- 菜单链接建议改成 `/m/login?wechat=1`；如果暂时不改菜单，登录页在“微信 UA + 无 ticket + 本次会话未跳过”时自动跳一次 OAuth，用 `sessionStorage` 标记防循环。
- 从 query 读 `wechatBindTicket`，存组件内存，登录请求带上。
- 页面显示轻提示：“已获取微信身份，登录后将绑定消息接收”。
- 登录成功：
  - `wechatBind.bound=true`：Toast“登录成功，微信消息已绑定”；
  - `wechatBind.bound=false`：先 Toast 登录成功，再弹 `Dialog` 显示后端 `message`，提供“知道了”，不阻断跳转。
- OAuth 失败/无 ticket：正常登录，不显示错误；登录后可在联系人页绑定。

`alert/contact/index.vue`：

- openid 列脱敏；未绑定显示“未绑定”。
- 操作列“绑定微信”“解绑”。
- 绑定弹窗用 `qrcodejs2` 展示 `/alert/contact/{id}/wechat/bind-url` 返回的 URL 二维码；提示“请先关注服务号，再用微信扫码”。
- 轮询 `bind-status`，成功后刷新列表。
- 解绑二次确认。

### 6.8 回调域名

推荐：

- `oauthPublicBaseUrl=https://api.netsightplus.cn/prod-api`
- 回调 `https://api.netsightplus.cn/prod-api/wechat/oauth/callback`
- 需要在 `wechat-test.conf` 增加 `/prod-api/` 反代，并在公众号后台“网页授权域名”加 `api.netsightplus.cn`。

零 Nginx 改动备选：

- `oauthPublicBaseUrl=http://www.netsightplus.cn/prod-api`
- 回调 `http://www.netsightplus.cn/prod-api/wechat/oauth/callback`
- 需要在公众号后台“网页授权域名”加 `www.netsightplus.cn`。
- 明文传输 code，仅作降级，不推荐生产。

`deploy_wechat_env.py` 生成 `wechat-test.conf` 时必须同步加入 `/prod-api/` 反代，避免下次部署被覆盖。

### 6.9 未关注用户

- OAuth 可以拿 openid；模板消息要求已关注，未关注发送返回 `43004`。
- 绑定成功页提示“请关注服务号，否则收不到模板消息”。
- 发送日志把 `43004` 标为“未关注”，不泛化失败。

### 6.10 openid 与 appId

- openid 按 appId 隔离；本期只有一个服务号，但绑定仍写 `wechat_app_id`。
- `NotificationContact` 增加 `wechat_app_id`、`wechat_bound_at`。
- `resolveReceiversByChannel(contacts, "wechat", appId)` 过滤：`wechatAppId` 为空（历史数据）或等于当前 `appId` 才返回。
- 发送时 appId 不匹配的联系人跳过，日志 `skipped/app_id_mismatch`。

---

## 7. 业务入口修复

### 7.1 派单 `WorkOrderNotifier`

现状：构造联系人只 set `name/mobile`，无 `wechatOpenid`；查主模板不过滤 `enabled`。

改造：

- 按 `tenantId + repairer.phone` 查 `notify_contact`，取 `status=1` 唯一记录，带上 `wechatOpenid/wechatAppId`。
- 查不到联系人：保留短信通道，不阻断工单；微信接收人为空，日志记“未绑定 openid”。
- 主模板查询只取 `enabled=1`，按 `templateCode=tpl_order_dispatch + tenantId` 取一条。
- 变量补下划线别名：`order_no/device_name/device_ip/device_type/device_location/location/severity/event_type/biz_id/tenant_name/repairer_name/fault_desc`，保留原驼峰变量兼容旧模板。
- afterCommit 调用，异常只记 error 不回滚工单，保持现状。

### 7.2 手动发送 `EventCenterService.dispatchManual`

现状：`vars` 只有 `content`；`dispatchByChannelIds` 没有主模板，手动发送永远不用模板。

改造：

- `vars.putAll(buildVars(event))`，再覆盖 `content`。
- `dispatchByChannelIds` 增加手动模板解析：按 `eventType` 映射 templateCode：
  - `manual_notify → tpl_manual`
  - `stock_low → tpl_stock_low`
  - `order_dispatch → tpl_order_dispatch`
  - `device_offline → tpl_device_offline`
  - `device_line_abnormal → tpl_device_line`
  - `device_recovered → tpl_device_recovered`
  - `device_cpu_high → tpl_device_cpu_high`
  - `device_mem_high → tpl_device_mem_high`
  - 其他 → `eventType`
- 按 `templateCode + tenantId + enabled=1` 取一条主模板；`findTemplate` 再按 `channelId` 取同组模板。
- 无模板时回落 `vars.content`，保持手动发送可用。

### 7.3 告警事件映射 `AlertPushService`

`ALERTNAME_MAP` 补：

- `CPU使用率过高`、`CPU过高`、`CPU高负载` → `device_cpu_high`
- `内存使用率过高`、`内存过高`、`内存高负载` → `device_mem_high`
- `设备外线状态异常`、`外线异常`、`外线链路异常` → `device_line_abnormal`
- 保持现有离线/恢复映射。
- `resolved` → `device_recovered`。

### 7.4 备件 `SparePartService`

现有 `stock_low` 已写 `deviceName=partName`、`location`、`labels.partName/partNo/partType/quantity/unit/safeStock/location`。

改造：

- 模板字段直接用 `part_name/part_no/quantity/safe_stock/location` 下划线别名；`buildVars` 补这些别名。
- 不再把备件名塞进 `device_name` 作为唯一语义；保留兼容。
- `content` 文案改为“备件 {partName}（{partNo}）库存 {quantity}，低于安全库存 {safeStock}”。

### 7.5 接收人解析 `NotificationContactService`

- `resolveReceiversByChannel(contacts, channelType, appId)` 新增 appId 过滤。
- 未绑定 openid 的告警条数改为按“未绑定联系人数”统计，不按重复 openid 计。
- `findByTenantAndOpenid`、`getForTenant` 新增。

### 7.6 通道去重键 `doneChannelTypes`

改为 `channelType#channelId`：

- 同一事件可用两个不同 wechat 实例；
- 同实例同模板仍只发一次；
- 避免现在“同类型第二实例被跳过”。

---

## 8. 配置与前端

### 8.1 渠道元数据

`NotifyChannelService` wechat meta 从 4 项扩到：

| Key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `appId` | text | 无 | 服务号 AppID |
| `appSecret` | password | 无 | AppSecret，加密存储 |
| `templateId` | text | 无 | 默认微信模板 ID |
| `url` | text | `http://www.netsightplus.cn/m/orders` | 默认跳转 |
| `mockMode` | switch | 跟随全局 | 实例优先 |
| `rateLimitPerMinute` | number | 4 | 单用户每分钟上限 |
| `dedupTtlHours` | number | 24 | 去重窗口 |
| `timeoutMs` | number | 5000 | 微信接口超时 |
| `retryIntervals` | text | `1000,2000` | 逐人重试间隔毫秒 |
| `oauthPublicBaseUrl` | text | `https://api.netsightplus.cn/prod-api` | OAuth 外部基址 |

`SECRET_FIELDS` 继续包含 `appSecret`；`maskConfig` 保持 `****`。

### 8.2 模板页

`alert/template/index.vue`：

- 通道 `channelType=wechat` 时显示：
  - 微信模板 ID
  - 跳转 URL
  - 字段映射表：字段名 / 变量 / 默认值 / 删除
  - “添加字段”
  - “恢复默认 5 字段”
  - “查看原始 JSON”
- 非 wechat 保持 textarea。
- 提交前把字段表序列化为 JSON 写入 `content`；`templateId` 可从渠道详情自动带出。
- `content` 仍必填；wechat 走 JSON 校验。
- `handleUpdate` 回填时解析 JSON 到字段表；解析失败回退 textarea 并提示。

### 8.3 联系人页

`alert/contact/index.vue`：

- openid 脱敏；未绑定 tag。
- “绑定微信”“解绑”。
- 绑定二维码弹窗；轮询结果。
- 绑定失败提示后端 `message`。

### 8.4 日志页

`alert/log/index.vue`：

- 接收人列支持展开逐人结果表：接收人（脱敏）/结果/错误码/错误信息/重试次数/跳过原因。
- 结果列：成功 / 失败 / 部分成功。
- 展示微信模板 ID、通道 ID、重试次数。
- `content` 对 wechat 显示 `renderedContent` 摘要，不显示 JSON 原文。

### 8.5 事件/规则页

`alert/event/index.vue`、`alert/rule/index.vue`：

- 事件类型下拉从 4 项扩到 8 项；
- `eventTypeText` 同步；
- 规则页保留现有条件匹配，不新增复杂条件编辑器。

---

## 9. 数据与审计

### 9.1 `notification_log` 新增列

```sql
ALTER TABLE notification_log ADD COLUMN channel_id BIGINT NULL;
ALTER TABLE notification_log ADD COLUMN template_id VARCHAR(128) NULL;
ALTER TABLE notification_log ADD COLUMN recipient_results_json TEXT NULL;
```

幂等执行：先查 `information_schema.columns`，不存在才 `ALTER`。

`NotificationLog` 实体同步新增：

```java
private Long channelId;
private String templateId;
private String recipientResultsJson;
```

### 9.2 `notify_contact` 新增列

```sql
ALTER TABLE notify_contact ADD COLUMN wechat_app_id VARCHAR(64) NULL;
ALTER TABLE notify_contact ADD COLUMN wechat_bound_at DATETIME NULL;
```

`NotificationContact` 实体同步。

### 9.3 日志写入规则

`NotificationChannelService`：

- `success`：全成功=1；部分/全失败=0。
- `content`：wechat 写 `SendResult.renderedContent`（可读摘要）；其他通道保持原渲染内容。
- `template_id`：实际使用的微信模板 ID。
- `channel_id`：实际通道实例 ID。
- `recipient_results_json`：`objectMapper.writeValueAsString(recipientResults)`。
- `retry_count`：通道层重试次数；wechat 取逐人最大 `retryCount`。
- `error_msg`：全失败写首个错误；部分成功写“X成功/Y失败”。

### 9.4 115 数据脚本

- 更新 `notification_template.id=43` 的 `content` 为 4.1 的 JSON（模板 ID 用现有 `-AItaLZ...`）。
- 为租户 7 预置以下 wechat 模板行（先建行、按需启用；模板 ID 未提供的先沿用 43 或留空，后台可改）：
  - `tpl_device_line`
  - `tpl_device_recovered`
  - `tpl_device_cpu_high`
  - `tpl_device_mem_high`
  - `tpl_order_dispatch`
  - `tpl_stock_low`
  - `tpl_manual`
- 不自动新增/启用规则，避免告警风暴；规则由管理员在页面按事件开启。
- 现有离线规则 `id=7/8` 不动。

---

## 10. 测试与验收

### 10.1 单元测试

- `WechatTemplatePayload`：
  - JSON 解析 / 空 content 回退默认；
  - 字段渲染、默认值、截断；
  - 事件文案映射。
- `WechatMpChannelSender`：
  - 用假 `WechatApiClient` 模拟 2 个 openid，一个成功一个 43004；
  - 部分成功 `success=false`，`recipientResults` 2 条；
  - 重复发送标 `duplicate`；
  - 第 5 条标 `rate_limit`；
  - 40001 触发 token 刷新一次。
- `NotificationChannelService`：
  - `recipientResults` 非空时 `sendWithRetry` 不整批重试。
- `WechatBindingService`：
  - 无联系人返回 `CONTACT_NOT_REGISTERED` 且不插入；
  - 停用返回 `CONTACT_DISABLED`；
  - 多联系人返回 `CONTACT_AMBIGUOUS`；
  - 唯一启用联系人绑定成功；
  - openid 冲突拒绝。

### 10.2 115 端到端验收

1. `mvn clean package -DskipTests` 通过，jar 部署 115，`start.sh` 重启，健康 200。
2. 执行幂等 SQL，`notification_log` 新列存在；`notify_contact` 新列存在；模板 43 `content` 为合法 JSON。
3. 手动发送 `manual_notify` 到联系人 3 + 通道 7：日志新增，`success=1`，`recipient_results_json` 有 1 条成功，微信收到。
4. 触发/模拟 `device_offline`：日志 `template_id` 为 43，逐人成功，未重复。
5. 同一 `bizId` 再发：逐人 `skipped/duplicate`，微信不重复收到。
6. 连续发 6 条：第 5/6 条 `skipped/rate_limit`，日志可见，不超频。
7. 派单给手机号 `15657477316` 的维修员：`order_dispatch` 日志 `success=1`，openid 来自联系人 3，不再是空接收人 fail。
8. 备件 `stock_low` 事件：模板字段显示备件名/编号/库存/位置。
9. 未登记手机号首次登录：登录成功、`notify_contact` 不新增行、返回 `CONTACT_NOT_REGISTERED`、前端提示联系管理员。
10. 管理员新增该手机号联系人并启用后，重新从公众号进入登录：绑定成功，`wechat_openid/wechat_app_id/wechat_bound_at` 写入。
11. 已绑定联系人解绑后再发送：日志“未绑定 openid”。
12. 未关注用户发送：日志明确 `43004 未关注`。
13. 旧模板空 content / 文本 content 仍走默认 5 字段，离线不回归。
14. `mockMode` 实例 false 真发；实例 true 日志明确 MOCK。
15. 生产未触碰，115 有 jar/dist/DB 备份。

### 10.3 构建与部署命令

后端：

```powershell
& "D:\DevTools\apache-maven-3.9.6\bin\mvn.cmd" -f "D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-server\pom.xml" clean package -DskipTests
```

前端：

```powershell
& "D:\DevTools\node16\npm.cmd" --prefix "D:\gitee\worktrees\NetSight1.0-wechat-mp-full-support\netsight-ui" run build:prod
```

115 部署沿用现有 `java-app-deploy` 连接参数与 `start.sh`，部署前备份 jar/dist/DB。

---

## 11. 风险与回滚

| 风险 | 处理 |
|---|---|
| 模板 ID 与字段不匹配，微信 47003 | 字段截断 + JSON 校验 + 日志显示实际模板 ID；后台可改 |
| 未关注 43004 | 绑定页提示关注；日志明确“未关注” |
| 频控 45009 | 默认 4 条/分钟/用户，可配；超限标 `rate_limit` |
| 出口 IP 不在白名单 40164 | 保持 115 出口 IP `115.159.194.149`；日志明确 IP 白名单错误 |
| OAuth 域名未配置 | 回调报 `redirect_uri 参数错误`；文档给出两种域名方案 |
| 绑定覆盖错人 | 不自动建联系人；openid/手机号冲突拒绝；只绑 `status=1` 唯一联系人 |
| 整批重试重复推送 | `recipientResults` 非空禁止整批重试 |
| 现有离线回归 | 空/非 JSON content 走默认 5 字段；现有规则/模板 43 不动 |
| 生产误发 | 本轮不碰生产；115 `mockMode` 可开关；部署前备份 |

回滚：

- 代码回滚到 `bfdcf47`；
- 115 恢复 jar/dist 备份；
- SQL 新列保留不影响旧代码；模板 43 `content` 回滚为空串；
- 新模板行可停用，不影响旧链路。

---

## 12. 实施阶段

### Phase 0：数据与基础模型

- `notification_log` / `notify_contact` DDL；
- `SendRequest`/`SendResult`/`RecipientResult` 扩展；
- `NotificationLog`/`NotificationContact` 实体字段；
- 模板 43 JSON 种子 SQL。

### Phase 1：模板描述符与发送器

- `WechatTemplatePayload`；
- `WechatTokenProvider`；
- `WechatApiClient`；
- `WechatMpChannelSender` 重写；
- 去重/频控；
- `NotificationChannelService` 逐人日志、禁止整批重试、`doneChannelKeys`。

### Phase 2：绑定与首次登录

- `WechatOAuthController` / `WechatOAuthService` / `WechatBindingService`；
- `SecurityConfig` 白名单；
- `AuthService.loginByPhone` 可选 ticket；
- `m/login.vue` OAuth 入口；
- `NotificationContact` 绑定列；
- 联系人页二维码绑定/解绑。

### Phase 3：业务入口修复

- `WorkOrderNotifier` 联系人 openid + enabled；
- `EventCenterService.dispatchManual` 变量 + 手动模板；
- `AlertPushService` CPU/内存/外线映射；
- `SparePartService` 备件变量别名；
- `NotificationContactService` appId 过滤与计数。

### Phase 4：前端配置与审计

- 渠道 meta 新字段；
- 模板页字段映射；
- 日志页逐人明细；
- 事件/规则 8 类下拉。

### Phase 5：115 部署与验收

- 编译、部署、SQL、Nginx（如需 api OAuth）；
- 15 项验收；
- 备份与回滚演练。

---

## 13. 关键决策记录

1. **方案 A**：模板描述符 + 通用 sender，否决每事件 Sender 子类和继续扩硬编码。
2. **不自动创建联系人**：首次登录只绑已登记且启用联系人，查不到提示联系管理员，登录不阻断。
3. **绑定与登录解耦**：openid 在登录前 OAuth 获取，登录成功后绑定；绑定失败不影响登录。
4. **逐人结果优先**：wechat 不做整批重试；重试在 sender 内按 openid 进行。
5. **模板 ID 不代建**：微信侧模板由运营创建，代码只配置 ID；115 先用现有 43 验证机制。
6. **生产不碰**：本轮只在 115 测试环境落地。
7. **独立分支**：`feature/wechat-mp-full-support`，基线 `bfdcf47`，不混入 `feature/repairer-user-account-binding`。
