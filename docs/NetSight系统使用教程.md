# NetSight（NAMS）系统使用教程

> 版本：V1.1
> 更新日期：2026-09-28
> 适用对象：第三方开发者、运维人员、系统管理员



***

## 目录



1. [系统概述](#1-系统概述)

2. [快速开始](#2-快速开始)

3. [认证与权限](#3-认证与权限)

4. [设备资产管理](#4-设备资产管理)

5. [边缘网关管理](#5-边缘网关管理)

6. [告警中心](#6-告警中心)

7. [工单管理](#7-工单管理)

8. [备品备件](#8-备品备件)

9. [点检巡检](#9-点检巡检)

10. [知识库故障库](#10-知识库故障库)

11. [通知公告](#11-通知公告)

12. [统一待办](#12-统一待办)

13. [监控大屏](#13-监控大屏)

14. [系统管理](#14-系统管理)

15. [H5 维修人员端](#15-h5维修人员端)

16. [API 接口规范](#16-api接口规范)

17. [多租户数据隔离](#17-多租户数据隔离)

18. [部署架构](#18-部署架构)

19. [业务数据流与类间调用关系](#19-业务数据流与类间调用关系)



***

## 1. 系统概述

NetSight（NAMS）是云边协同的物联网设备状态采集、分析与异常报警平台，统一纳管网络设备、视频监控、门禁安防设备。

### 核心能力



* **边缘采集**：Prometheus + Blackbox Exporter 在边缘网关采集设备指标，指标不出内网

* **告警上云**：仅告警事件通过 Alertmanager Webhook 推送至云端

* **多租户**：基于 MyBatis Plus 多租户插件，数据天然隔离

* **全通道通知**：PushPlus、阿里云短信、腾讯云短信、钉钉机器人、企业微信、飞书、邮件等

* **工单闭环**：告警自动生成工单 → 派单 → H5 维修 → 完工归档

### 技术栈



| 层   | 技术                                                                 |
| --- | ------------------------------------------------------------------ |
| 前端  | Vue2 + Element UI（若依分离版二次开发）                                       |
| 后端  | Spring Boot 3 + MyBatis Plus + Spring Security + JWT               |
| 数据库 | MySQL 8 + Redis                                                    |
| 边缘  | nams-agent（Java HTTP Server）+ Prometheus + Alertmanager + Blackbox |
| H5  | Vue2 + Vant 2.x                                                    |



***

## 2. 快速开始

### 访问地址



| 端       | 地址                                                       | 说明                |
| ------- | -------------------------------------------------------- | ----------------- |
| PC 管理端  | [http://192.168.1.55/](http://192.168.1.55/)             | 管理员 / 运维使用        |
| H5 维修端  | [http://192.168.1.55/m/](http://192.168.1.55/m/)         | 维修人员使用            |
| 边缘网关管理页 | [http://192.168.1.60:18081/](http://192.168.1.60:18081/) | nams-agent Web 管理 |

### 测试账号



| 角色    | 手机号         | 密码 / 验证码   | 租户   |
| ----- | ----------- | ---------- | ---- |
| 超级管理员 | 18667800006 | 开发码 123456 | 租户 1 |
| 租户管理员 | 15657477316 | 开发码 123456 | 租户 7 |
| 运维人员  | 15700000001 | 开发码 123456 | 租户 7 |
| 维修人员  | 15700000002 | 开发码 123456 | 租户 7 |

> 开发环境验证码统一为 
>
> `123456`
>
> ，生产环境通过阿里云短信真实下发。



***

## 3. 认证与权限

### 登录流程



```
用户输入手机号 → POST /auth/sms-code?phone=xxx（获取验证码）
             → POST /auth/login {phone, code, clientType}
             → 返回 JWT Token（720分钟有效）
             → 后续请求 Header: Authorization: Bearer <token>
```

### 权限模型



* **RBAC**：用户 → 角色 → 权限

* **四种角色**：super\_admin（超管）、tenant\_admin（租户管理员）、ops（运维）、repairer（维修）

* **前端**：`v-hasPermi` / `v-hasRole` 控制按钮显隐

* **后端**：`@PreAuthorize` 注解控制接口访问

### 登出



```
POST /auth/logout → Token 加入 Redis 黑名单（剩余有效期内失效）
```



***

## 4. 设备资产管理

### 设备列表



* **路径**：设备资产 → 设备列表

* **API**：`GET /device/list?pageNum=1&pageSize=20`

* **字段**：设备编码、名称、IP、类型、品牌、位置、状态、保修期

### 设备操作



| 操作  | 说明                                |
| --- | --------------------------------- |
| 新增  | 填写设备信息，关联边缘网关                     |
| 修改  | 编辑设备属性                            |
| 删除  | 逻辑删除（del\_flag=1）                 |
| 二维码 | 生成设备标签二维码，用于现场扫码                  |
| 保修期 | warranty\_expire 字段，自动派生在保 / 过保状态 |

### 设备状态



* `status`：0 = 离线 1 = 在线（由边缘网关上报）

* `line_status`：链路状态

* 状态由边缘网关每 30 秒批量上报，原子更新



***

## 5. 边缘网关管理

### 网关注册



1. 云端：边缘网关 → 新增网关，获得 **Gateway Token**

2. 边缘网关注入 Token：`/opt/nams-agent/config.yml`

3. nams-agent 启动后主动向云端发起心跳（每 60 秒）

### 心跳与映射



```
nams-agent (每60s)
  → POST /edge/report/heartbeat（带 Gateway Token）
  → GET  /edge/config/mapping（拉取设备采集配置）
  → 生成本地 targets/snmp.json（file_sd，60s刷新）
```

### 设备状态上报



```
nams-agent (每30s 查询本地 Prometheus)
  → POST /edge/report/status（批量上报设备 up/down）
  → 云端原子更新 device.status
```



***

## 6. 告警中心

### 告警链路



```
Prometheus 采集 → Alertmanager 告警规则触发
  → Webhook POST /alert/push（带 X-Netsight-Webhook-Token）
  → AlertPushController 鉴权（Token → tenantId）
  → EventCenterService.asyncProcess()
    → 创建 EventRecord
    → 24小时去重
    → 匹配通知规则（按 event_type + tenantId）
    → dispatchByTemplateGroup()
      → 按模板组加载多通道实例
      → 渲染模板变量（buildVars）
      → ChannelSender.send() 逐通道发送
      → 记录 NotificationLog
```

### 模块说明



| 模块    | 路径           | 说明            |
| ----- | ------------ | ------------- |
| 事件记录  | 告警中心 → 事件记录  | 所有告警事件，支持手动发送 |
| 通知规则  | 告警中心 → 通知规则  | 按事件类型绑定消息模板   |
| 消息模板  | 告警中心 → 消息模板  | 模板内容 + 绑定通道实例 |
| 通知通道  | 告警中心 → 通知通道  | 通道实例管理（含参数配置） |
| 发送日志  | 告警中心 → 发送日志  | 每条通知的发送结果     |
| 通知联系人 | 告警中心 → 通知联系人 | 接收人维护         |

### 通道类型



| 类型           | 说明            | 参数                                                   |
| ------------ | ------------- | ---------------------------------------------------- |
| pushplus     | PushPlus 微信推送 | token                                                |
| aliyun\_sms  | 阿里云短信         | accessKeyId, accessKeySecret, signName, templateCode |
| tencent\_sms | 腾讯云短信         | secretId, secretKey, signName, templateId            |
| dingtalk     | 钉钉机器人         | webhook, secret                                      |
| wechat\_work | 企业微信          | corpId, corpSecret, agentId                          |
| feishu       | 飞书机器人         | webhook                                              |
| email        | 邮件            | smtp host, port, username, password                  |



***

## 7. 工单管理

### 工单状态机



```
0=待派单 → 1=已派单(待接单) → 2=维修中 → 3=已完成
                                      ↘ 5=已自动恢复
```

### 操作流程



1. **自动生成**：设备离线告警自动创建工单（faultType=auto）

2. **手动创建**：工单管理 → 新增工单

3. **派单**：选择维修人员，自动发送通知（短信 + PushPlus）

4. **H5 接单**：维修人员在 H5 端查看并开始维修

5. **完工**：填写维修结果 + 上传照片（必传）+ 选择配件

6. **自动恢复**：设备恢复在线时，未完成工单自动标记为已恢复

### 派单通知



* 派单后通过 `WorkOrderNotifier` 异步发送通知

* 通道：按模板组配置（短信 + PushPlus）

* 模板：`tpl_order_dispatch`



***

## 8. 备品备件

### 功能



* 备件种类管理（名称、型号、单位、安全库存）

* 库存数量实时更新

* 出入库流水记录

* 低库存自动预警

### 出入库



```
POST /spare/part/stock
Body: {partId, quantity（正=入库，负=出库）, type, remark, workOrderId?}
```



***

## 9. 点检巡检

### 三级结构



```
点检项库（检查项定义）→ 点检计划（周期+目标+执行人）→ 点检任务（自动生成）
```

### 任务执行



* 维修 / 运维人员在 "点检任务" 页查看待执行任务

* 逐项填写：正常 / 异常 + 文本结果

* 异常项自动生成报修工单（可选）

### 自动生成



* 每天 00:10 定时任务扫描计划，自动生成次日点检任务

* 唯一键防重：(tenant\_id, plan\_id, target\_id, plan\_date)



***

## 10. 知识库故障库

### 功能



* 故障知识条目：标题、分类、设备类型、品牌、现象、原因、处理步骤

* 工单详情联动推荐：按设备类型 + 故障类型自动匹配

* 浏览计数自动累加

### 适用设备类型

`all`（通用）/ `network`（网络设备）/ `camera`（视频）/ `nvr` / `door_controller`（门禁）



***

## 11. 通知公告

### 状态



* 草稿 → 已发布 → 已下线

* 支持置顶、过期时间

* 用户端已读状态记录

### 铃铛通知



* 顶部铃铛显示未读数（60 秒轮询）

* 点击查看通知列表

* 自动标记已读



***

## 12. 统一待办

### 聚合来源



| 类型     | 说明                  |
| ------ | ------------------- |
| 工单待派单  | 状态 = 0 的工单（仅管理员可见）  |
| 我的维修工单 | 派给当前维修人员的工单（状态 1/2） |
| 待执行点检  | 分配给当前用户的点检任务        |

### 角标



* 顶部铃铛实时显示待办总数

* 点击 "去处理" 跳转到对应业务页面



***

## 13. 监控大屏



* **路径**：监控大屏

* **API**：`GET /dashboard/overview`

* **内容**：设备总数 / 在线数、网关状态、实时告警、工单统计、备件库存、告警级别分布

* **全屏模式**：自适应布局，支持浏览器全屏



***

## 14. 系统管理

### 用户管理



* 新增 / 修改 / 删除用户

* 分配角色（一个用户可多角色）

* 重置密码（仅超管）

### 角色管理



* 角色定义 + 权限分配（菜单 + 按钮）

* 内置角色：super\_admin /tenant\_admin/ops /repairer

### 租户管理



* 租户 CRUD

* 每个租户独立 Webhook Token（AES 加密存储）

* 每个租户独立 PushPlus Token

* 删除租户级联清理业务数据



***

## 15. H5 维修人员端

### 访问



* 地址：[http://192.168.1.55/m/](http://192.168.1.55/m/)

* 登录：手机号 + 验证码（与 PC 端同一套认证）

* 仅关联了维修人员档案的账号可登录

### 页面



| 页面   | 路径            | 功能                  |
| ---- | ------------- | ------------------- |
| 登录   | /m/login      | 手机号验证码登录            |
| 工单列表 | /m/orders     | 待接单 / 维修中 / 已完成 Tab |
| 工单详情 | /m/order/{id} | 接单 / 开工 / 完工        |

### 完工要求



* 维修结果必填

* 照片至少 1 张（上传至 /opt/nams-upload/）

* 可选择消耗配件



***

## 16. API 接口规范

### 统一返回格式



```
{
  "code": 200,
  "msg": "操作成功",
  "data": {},
  "traceId": "xxx"
}
```

### 分页返回



```
{
  "code": 200,
  "data": {
    "total": 100,
    "rows": [],
    "code": 200
  }
}
```

### 认证方式



```
Header: Authorization: Bearer <JWT Token>
```

### 错误码



| code | 含义             |
| ---- | -------------- |
| 200  | 成功             |
| 401  | 未认证 / Token 无效 |
| 403  | 无权限            |
| 500  | 系统内部错误         |
| 5003 | 验证码错误或过期       |
| 5006 | 请求过于频繁         |



***

## 17. 多租户数据隔离

### 实现方式



* **MyBatis Plus TenantLineInnerInterceptor** 自动在 SQL 中注入 `tenant_id` 条件

* 超管（super\_admin）查询时忽略租户条件（全租户总览）

* 每个表都有 `tenant_id` 字段

### 隔离验证



* 租户 A 无法查看租户 B 的设备、工单、告警

* 跨租户访问详情返回 "不存在"（防探）

* Webhook Token 按租户独立鉴权



***

## 18. 部署架构



```
[内网设备]
    ↓ ICMP/SNMP
[边缘网关 192.168.1.60]
  ├── Prometheus (:9090)
  ├── Alertmanager (:9093)
  ├── Blackbox (:9115)
  └── nams-agent (:18080/18081)
    ↓ 主动出站（HTTPS/HTTP）
[云端服务器 192.168.1.55]
  ├── Nginx (:80)
  │   ├── / → 前端静态文件
  │   ├── /prod-api/ → Spring Boot (:8080)
  │   ├── /m/ → H5静态文件
  │   └── /alert/push → 告警Webhook
  ├── Spring Boot (:8080)
  ├── MySQL 8
  └── Redis
```

### 关键端口



| 服务           | 端口                      |
| ------------ | ----------------------- |
| Nginx        | 80                      |
| Spring Boot  | 8080                    |
| MySQL        | 3306                    |
| Redis        | 6379                    |
| Prometheus   | 9090                    |
| Alertmanager | 9093                    |
| Blackbox     | 9115                    |
| nams-agent   | 18080(API) / 18081(Web) |



***

## 19. 业务数据流与类间调用关系

> 本章详细描述每个核心业务流在 Spring 各层之间的调用链，供第三方开发者理解系统内部工作机制。

### 19.1 告警接入全链路（Webhook → 通知发送）

这是系统最核心的数据流，从 Alertmanager 推送告警到最终通知送达。



```
[边缘网关 Alertmanager]
  │ POST /alert/push
  │ Header: X-Netsight-Webhook-Token: <租户Token>
  ▼
┌─────────────────────────────────────────────────────────┐
│ AlertPushController.push()                               │
│  ├─ WebhookTokenService.resolveTenantId(token)        │
│  │   └─ Redis缓存 → sys_tenant.webhook_token(内存解密比对)│
│  │   └─ 返回 tenantId                                    │
│  └─ AlertPushService.push(body, tenantId)              │
│      ├─ parseToEvents(body, tenantId)                 │
│      │   ├─ AlertManager v4报文: alerts[]循环            │
│      │   │   └─ buildFromAlertManager()                  │
│      │   │       ├─ ALERTNAME_MAP映射中文alertname→event_type│
│      │   │       └─ 构造EventRecord对象                  │
│      │   └─ 标准格式: event_type字段                     │
│      │       └─ buildFromStandard()                    │
│      └─ for each EventRecord:                           │
│          └─ EventCenterService.receiveEvent(event)     │
└─────────────────────────────────────────────────────────┘
  │
  ▼ @Transactional(REQUIRES_NEW) 同步入库
┌─────────────────────────────────────────────────────────┐
│ EventCenterService.receiveEvent()                       │
│  ├─ eventMapper.insert(event)  → event_record表        │
│  └─ selfProvider.getObject().asyncProcess(eventId)    │
│      └─ @Async("eventExecutor") 异步线程池             │
└─────────────────────────────────────────────────────────┘
  │
  ▼ 异步处理（eventExecutor线程）
┌─────────────────────────────────────────────────────────┐
│ EventCenterService.asyncProcess(eventId)                │
│  1. eventMapper.selectById(eventId)  查询事件           │
│  2. eventPublisher.publishEvent(new AlertEvent(event)) │
│     └─ [事件驱动] WorkOrderEventListener.onAlertEvent() │
│         ├─ device_offline/device_line_abnormal:        │
│         │   └─ WorkOrderService.autoCreateFromEvent()  │
│         │       └─ 自动创建工单(24h去重)                │
│         └─ device_recovered:                            │
│             └─ WorkOrderService.handleRecover()        │
│                 └─ 自动归档未完成工单(status=5)          │
│  3. device_recovered时清除Redis去重键                   │
│  4. eventMapper.updateById() 状态改为processing          │
│  5. ruleService.listEnabledRules(tenantId)              │
│     └─ 按event_type过滤匹配通知规则                      │
│  6. ruleService.parseContactIds(rule)                  │
│     └─ contactService.resolveContacts(ids, tenantId)   │
│         └─ 查询notification_contact表                   │
│  7. buildVars(event) 组装模板变量                       │
│     ├─ device_name/device_ip/device_type/location       │
│     ├─ severity/event_type/biz_id                       │
│     ├─ resolveTenantName(tenantId) → sys_tenant表      │
│     └─ labels_json合并进vars                             │
│  8. Redis去重: setIfAbsent(dedupKey, 24h TTL)          │
│  9. channelService.dispatch(event, rule, contacts, vars)│
└─────────────────────────────────────────────────────────┘
  │
  ▼ 通道分发
┌─────────────────────────────────────────────────────────┐
│ NotificationChannelService.dispatch()                   │
│  ├─ rule.templateId != null → dispatchByTemplateGroup()│
│  │   ├─ templateMapper.selectOne(rule.templateId)       │
│  │   ├─ templateMapper.selectList(templateCode同组)     │
│  │   └─ for each template:                              │
│  │       ├─ resolveChannelInstance(tpl, tenantId)      │
│  │       │   ├─ tpl.channelId != null:                  │
│  │       │   │   └─ notifyChannelService.resolveChannels([id])│
│  │       │   │       └─ 查notify_channel表+解密config    │
│  │       │   └─ tpl.channelId == null:                  │
│  │       │       └─ resolveDefaultByType(type, tenantId)│
│  │       ├─ contactService.resolveReceiversByChannel()   │
│  │       │   └─ sms→mobile, wechat→openid, pushplus→空 │
│  │       └─ sendOneResolved()                          │
│  │           ├─ channelRegistry.get(channelType)       │
│  │           │   └─ Spring自动注入List<Sender>，按type匹配│
│  │           ├─ NotificationTemplateService.render()    │
│  │           │   └─ {{var}}模板替换                      │
│  │           ├─ SendRequest.builder()构造请求            │
│  │           ├─ sendWithRetry(sender, request)         │
│  │           │   └─ sender.send() → 外部API             │
│  │           │       ├─ PushplusChannelSender → PushPlus API│
│  │           │       ├─ AliyunSmsChannelSender → 阿里云短信API│
│  │           │       ├─ DingtalkChannelSender → 钉钉机器人Webhook│
│  │           │       └─ ...其他Sender                   │
│  │           └─ logMapper.insert(notification_log)     │
│  └─ rule == null → dispatchByChannelIds() [手动发送]    │
└─────────────────────────────────────────────────────────┘
  │
  ▼ 最终
┌─────────────────────────────────────────────────────────┐
│ 1. eventMapper.updateById() 状态→sent/failed/part_failed│
│ 2. PushWebSocketHandler.broadcast() 实时推送到前端大屏  │
└─────────────────────────────────────────────────────────┘
```

**涉及的关键类**：



| 类                             | 职责                          |
| ----------------------------- | --------------------------- |
| `AlertPushController`         | Webhook 入口，Token 鉴权         |
| `WebhookTokenService`         | Token→tenantId 反查（Redis 缓存） |
| `AlertPushService`            | 报文解析，ALERTNAME\_MAP 映射      |
| `EventCenterService`          | 事件入库、异步处理、规则匹配、去重           |
| `NotificationRuleService`     | 通知规则查询与条件匹配                 |
| `NotificationContactService`  | 联系人解析（按通道类型分流接收人）           |
| `NotificationChannelService`  | 通道分发、模板渲染、重试、日志             |
| `ChannelRegistry`             | SPI 路由（List自动发现）            |
| `NotifyChannelService`        | 通道实例加载（解密 AES 加密的 config）   |
| `NotificationTemplateService` | 模板内容渲染                      |
| `*ChannelSender`（10 个实现）      | 各通道实际发送逻辑                   |
| `WorkOrderEventListener`      | 告警→工单联动                     |



***

### 19.2 边缘网关状态上报



```
[nams-agent 每30s查询本地Prometheus]
  │ POST /edge/report/status
  │ Header: X-Gateway-Token: <网关Token>
  │ Body: {"devices":[{"deviceCode":"DEVxxx","up":1,"lineAbnormal":0}]}
  ▼
GatewayReportController.reportStatus()
  ├─ EdgeGatewayService.getByToken(token)
  │   └─ 全量拉取网关列表 + 内存decrypt比对（AES随机IV禁止等值SQL）
  ├─ EdgeGatewayService.heartbeat(gateway, ip)
  │   └─ 更新last_heartbeat_time + online_status=1
  ├─ DeviceService.updateStatusBatch(devices, gatewayId)
  │   └─ LambdaUpdateWrapper原子UPDATE:
  │       UPDATE device SET status=?, line_status=?
  │       WHERE gateway_id=? AND device_code=? AND del_flag=0
  └─ PushWebSocketHandler.broadcast("device-status", ...)
      └─ 前端实时刷新设备状态
```

**心跳接口**：



```
POST /edge/report/heartbeat
  └─ GatewayReportController.heartbeat()
      └─ EdgeGatewayService.getByToken() → heartbeat()
```

**Mapping 拉取接口**：



```
GET /edge/config/mapping
  └─ EdgeConfigController.getMapping()
      └─ 查询该网关下所有启用设备 → 生成targets JSON
      └─ nams-agent每60s拉取 → 写入本地targets/snmp.json
      └─ Prometheus file_sd 60s自动刷新
```



***

### 19.3 工单全生命周期



```
[创建工单]
  ├─ 自动: EventCenterService.asyncProcess()
  │   └─ WorkOrderEventListener.onAlertEvent()
  │       └─ WorkOrderService.autoCreateFromEvent(event)
  │           └─ 24h去重检查 → workOrderMapper.insert()
  └─ 手动: WorkOrderController.addOrder()
      └─ WorkOrderService.addOrder()
          └─ workOrderMapper.insert()

[派单]
POST /workorder/order/dispatch body={id, repairerId}
  └─ WorkOrderController.dispatch()
      └─ WorkOrderService.dispatch(id, repairerId)
          ├─ orderMapper.selectById() + checkTenantOrder()
          ├─ 状态更新: status=1 (已派单)
          ├─ repairerMapper.selectById(repairerId)
          ├─ orderRecordMapper.insert() (操作记录)
          └─ TransactionSynchronization.afterCommit:
              └─ WorkOrderNotifier.notifyRepairer(order, repairer)
                  ├─ templateMapper.selectOne(tpl_order_dispatch)
                  ├─ 构造EventRecord + 临时NotificationRule
                  ├─ 构造NotificationContact(维修人员手机)
                  └─ channelService.dispatch(event, rule, contacts, vars)
                      └─ [复用19.1通道发送链路]

[H5开工]
POST /m/order/{id}/start
  └─ MobileWorkOrderController.start()
      └─ WorkOrderService.startRepair(id)
          └─ 状态更新: status=2 (维修中)

[H5完工]
POST /m/order/{id}/complete
  body: {repairResult, photos[], parts[]}
  └─ MobileWorkOrderController.complete()
      └─ WorkOrderService.completeRepair()
          ├─ 状态更新: status=3 (已完成)
          ├─ workOrder.setRepairResult/repairPhotos
          ├─ for each parts:
          │   └─ SparePartService.stock(partId, -qty)
          │       └─ 原子UPDATE: quantity=quantity-N
          └─ orderRecordMapper.insert()

[设备自动恢复]
  └─ WorkOrderEventListener → WorkOrderService.handleRecover()
      └─ 查找同bizId未完成工单 → status=5 (已自动恢复)
```



***

### 19.4 登录认证流程



```
[PC/H5统一入口]
POST /auth/sms-code?phone=18667800006
  └─ AuthController.sendSmsCode()
      └─ AuthService.sendSmsCode()
          ├─ 开发环境: Redis存code=123456
          └─ 生产环境: 调阿里云短信通道真实下发

POST /auth/login {phone, code, clientType}
  └─ AuthController.login()
      └─ AuthService.loginByPhone()
          ├─ Redis验证码校验 + 一次性消费
          ├─ sys_userMapper.selectByPhone()
          ├─ 用户角色加载(sys_user_role + sys_role)
          ├─ H5端额外校验: 必须关联repairer表
          ├─ JWT Token生成(720分钟有效)
          └─ Redis存储登录态

后续请求:
  Header: Authorization: Bearer <token>
  └─ JwtAuthenticationFilter (OncePerRequestFilter)
      ├─ JWT解析 → 提取userId
      ├─ Redis黑名单检查(登出后失效)
      ├─ 加载LoginUser → SecurityContextHolder
      └─ TenantLineInnerInterceptor自动注入tenant_id条件

POST /auth/logout
  └─ AuthService.logout()
      └─ Redis写入黑名单: blacklist:{userId}:{tokenHash} (剩余TTL)
```



***

### 19.5 设备 CRUD 流程



```
GET /device/list
  └─ DeviceController.list()
      └─ DeviceService.pageDevice()
          ├─ Page<Device>分页
          ├─ LambdaQueryWrapper条件过滤(MyBatisPlus多租户自动加tenant_id)
          └─ fillDisplayFields() 派生字段(warrantyStatus等)

POST /device (新增)
  └─ DeviceController.add()
      └─ DeviceService.addDevice()
          └─ deviceMapper.insert() (自动填充tenantId)

PUT /device (修改)
  └─ DeviceController.update()
      └─ DeviceService.updateDevice()
          └─ deviceMapper.updateById() (MP NOT_NULL策略)
          └─ 保修期清空: clearWarranty=true时用LambdaUpdateWrapper强制set null

DELETE /device/{id}
  └─ DeviceController.delete()
      └─ DeviceService.deleteDevice()
          └─ 逻辑删除: del_flag=1

GET /device/qrcode/{id}
  └─ DeviceController.qrcode()
      └─ DeviceService.getQrcodeInfo()
          └─ selectById + checkTenantPermission()
```



***

### 19.6 多租户隔离工作机制



```
所有SQL查询自动经过MyBatisPlus拦截器链:

JwtAuthenticationFilter
  └─ SecurityContextHolder.getContext() → LoginUser
      └─ SecurityUtils.getTenantId()

TenantLineInnerInterceptor (MyBatisPlus)
  ├─ 非超管: 自动在SQL追加 WHERE tenant_id = ?
  └─ 超管(isSuperAdmin): 忽略租户条件，全租户总览

示例:
  SELECT * FROM device WHERE del_flag=0 AND tenant_id=7
  (非超管自动追加tenant_id=7)

  SELECT * FROM device WHERE del_flag=0
  (超管不追加tenant_id)
```

**超管判定**：用户角色列表包含 `super_admin`，由 `SecurityUtils.isSuperAdmin()` 判断。



***

### 19.7 切面类工作机制



| 切面类                          | 切点                       | 作用                                                                             |
| ---------------------------- | ------------------------ | ------------------------------------------------------------------------------ |
| `OperLogAspect`              | `@Log`注解 + Controller 方法 | 记录操作日志 (操作人 / URI/IP/ 参数 / 耗时) → event\_record 表 (event\_type=user\_operation) |
| `RateLimitAspect`            | `@RateLimit`注解           | 基于 Redis 的接口限流 (如验证码 5 次 / 分钟)                                                 |
| `WebRequestLogAspect`        | 所有 Controller 请求         | 记录 HTTP 请求日志 (方法 / 路径 / 状态码 / 耗时)                                              |
| `TraceIdFilter`              | 所有请求                     | 生成 / 透传 X-Trace-Id → MDC → 响应头                                                 |
| `TenantLineInnerInterceptor` | 所有 MyBatis 查询            | 自动注入 tenant\_id 条件                                                             |



***

### 19.8 实时推送 (WebSocket)



```
设备状态变更:
  GatewayReportController.reportStatus()
  └─ PushWebSocketHandler.broadcast("device-status", data)

新告警事件:
  EventCenterService.asyncProcess()
  └─ PushWebSocketHandler.broadcast("event", data)

前端订阅:
  WebSocket连接 /ws/push
  └─ onmessage → 实时更新大屏/告警列表/设备状态
```