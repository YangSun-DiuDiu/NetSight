# 边缘网关设备清单本地录入与云端同步设计

- 日期：2026-11-24
- 状态：设计待评审
- 范围：NetSight 边缘网关 `nams-agent` 与云端 `netsight-server` 的设备清单管理
- 目标版本：NetSight 1.0 设备清单同步改造

## 1. 背景

当前设备清单由云端维护，网关通过 `GET /edge/config/mapping` 拉取。`MappingSync` 的逻辑是“云端优先，云端不可用时回退本地 `mapping.json`”。

实际项目中，设备由租户在边缘网关现场录入。云端录入存在两个问题：

1. 租户对现场设备、IP、位置的掌握不如网关侧直接，容易录错或漏录。
2. 云端设备信息错误会导致网关没有生成正确的 ping target，设备告警无法产生或无法关联。

因此需要把设备清单的权威源改为边缘网关，云端只接收同步结果，不再提供设备编辑能力。

## 2. 目标与非目标

### 2.1 目标

1. 设备新增、编辑、删除全部在网关本地管理页面完成。
2. 网关本地 `mapping.json` 是设备清单唯一权威源。
3. 网关按固定周期把全量清单推送到云端同租户。
4. 云端按 `gateway_id + device_code` 幂等更新，支持软删除。
5. 云端租户侧设备管理改为只读，禁止新增、编辑、删除。
6. 同步失败可重试，本地清单不丢失。
7. 新增设备后能尽快生成 ping target，并保证云端能收到后续状态和告警。

### 2.2 非目标

1. 不实现云端与网关双向同步。
2. 不实现云端设备编辑冲突合并。
3. 不改变 ping 采集方式、Prometheus 规则和告警接收链路。
4. 不改变设备运行状态 `status`、`line_status` 的更新逻辑。
5. 不实现 CSV/Excel 批量导入；本期只做页面单条增删改。

## 3. 核心原则

1. **单一权威源**：网关本地清单是唯一可信设备清单。
2. **单向同步**：网关 → 云端，云端不回写设备配置。
3. **配置与状态分离**：同步只更新设备配置字段，不覆盖在线状态。
4. **幂等**：同一份清单重复推送不产生重复设备。
5. **删除可追溯**：云端软删除，保留历史事件和告警。
6. **租户隔离**：云端通过网关 Token 推导租户，不信任请求体租户字段。
7. **失败不阻塞本地**：云端不可达时，本地增删改和 ping 采集继续工作。

## 4. 总体架构

```text
┌─────────────────────────────────────────────────────┐
│ 边缘网关 nams-agent                                  │
│                                                     │
│  本地管理页面 /local                                │
│        │ 新增 / 编辑 / 删除                         │
│        ▼                                            │
│  LocalDeviceService                                │
│        │ 原子写入 + revision+1                      │
│        ▼                                            │
│  conf/mapping.json（唯一权威源）                    │
│        │                                            │
│        ├── MappingSync 重新加载                     │
│        │       └── 生成 conf/targets/ping.json      │
│        │                                            │
│        └── InventorySync                            │
│               ├── 定时 5 分钟                       │
│               ├── 本地变更后立即触发                │
│               └── 失败退避重试                      │
└──────────────────────┬──────────────────────────────┘
                       │ POST /edge/config/devices/sync
                       │ X-Gateway-Token
                       ▼
┌─────────────────────────────────────────────────────┐
│ 云端 netsight-server                                 │
│                                                     │
│  EdgeInventoryController                            │
│        │ Token → gateway → tenant_id                │
│        ▼                                            │
│  DeviceInventorySyncService                         │
│        ├── 按 gateway_id + device_code 幂等 upsert  │
│        ├── 全量清单缺失设备软删除                   │
│        └── 更新 edge_gateway 同步状态               │
│                                                     │
│  device 表（source=edge，tenant_id 来自网关）        │
│  云端设备页面：只读                                  │
└─────────────────────────────────────────────────────┘
```

## 5. 本地网关设计

### 5.1 本地清单文件

继续使用 `conf/mapping.json`，增加同步元数据：

```json
{
  "version": "local-20261124-001",
  "revision": 12,
  "updatedAt": "2026-11-24 10:00:00",
  "devices": [
    {
      "deviceCode": "DEV-GW19920261122192456-0001",
      "deviceName": "48盘位磁盘阵列",
      "deviceIp": "192.168.1.55",
      "deviceType": "nvr",
      "location": "监控中心机房",
      "brand": "海康威视",
      "model": "DS-9632",
      "collectType": "ping",
      "collectPort": 0,
      "warrantyExpire": null
    }
  ]
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `version` | 是 | 本地清单版本标识，便于排查 |
| `revision` | 是 | 单调递增整数，每次增删改加 1 |
| `updatedAt` | 是 | 本地最后修改时间 |
| `devices` | 是 | 设备数组，不允许为 `null` |
| `deviceCode` | 是 | 设备唯一编码，同一网关内唯一 |
| `deviceName` | 是 | 设备名称 |
| `deviceIp` | 是 | 管理 IP，IPv4 |
| `deviceType` | 是 | `network` / `camera` / `nvr` / `door_controller` |
| `location` | 否 | 部署位置 |
| `brand` | 否 | 品牌 |
| `model` | 否 | 型号 |
| `collectType` | 是 | 固定 `ping` |
| `collectPort` | 是 | 固定 `0`，兼容历史字段 |
| `warrantyExpire` | 否 | 保修截止日期，`YYYY-MM-DD` |

### 5.2 清单加载规则

1. `MappingSync` 不再调用云端 `GET /edge/config/mapping`，只加载本地 `mapping.json`。
2. 文件不存在时初始化为空清单，`revision=1`，允许本地页面录入第一台设备。
3. 文件存在但缺少 `revision` 时，初始化为 `1`，并在下一次写回时补齐。
4. 文件中的 `devices` 字段存在但不是数组时，视为清单损坏，保留备份并拒绝启动同步。
5. `MappingSync` 只负责加载本地清单、维护内存设备列表、生成 `ping.json`。
6. 云端推送由新的 `InventorySync` 独立负责，避免清单加载和网络同步耦合。

### 5.3 写入规则

1. 使用临时文件写入，`fsync` 后原子替换 `mapping.json`。
2. 写入前备份为 `mapping.json.bak-<时间戳>`，保留最近 20 份。
3. 每次成功增删改后 `revision += 1`，更新 `updatedAt`。
4. 写入成功后调用 `MappingSync.reloadLocal()` 重新加载清单并生成 `ping.json`。
5. 写入成功后调用 `InventorySync.syncNow()` 立即推送云端。
6. 多线程访问使用文件锁或服务内 `ReentrantLock`，避免并发写坏。

### 5.4 本地 API

在 `LocalApiController` 增加以下接口，全部位于 `/local/api` 下，受现有 `LocalAuthFilter` 保护：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/local/api/devices` | 设备列表，包含实时状态与同步状态 |
| `POST` | `/local/api/devices` | 新增设备 |
| `PUT` | `/local/api/devices/{deviceCode}` | 编辑设备 |
| `DELETE` | `/local/api/devices/{deviceCode}` | 删除设备 |
| `POST` | `/local/api/devices/sync` | 立即推送云端 |
| `GET` | `/local/api/devices/sync-status` | 最近同步结果 |

新增请求体示例：

```json
{
  "deviceCode": "",
  "deviceName": "车间枪机",
  "deviceIp": "192.168.20.21",
  "deviceType": "camera",
  "location": "1号车间东墙",
  "brand": "海康威视",
  "model": "DS-2CD3T46",
  "warrantyExpire": "2028-12-31"
}
```

`deviceCode` 为空时由服务端自动生成，规则为：

```text
DEV-<gatewayCode>-<4位序号>
```

创建时允许手工填写或留空自动生成；生成后在同一网关内保证唯一。创建成功后设备编码不可修改，编辑接口忽略 `deviceCode` 字段。历史设备编码（如 `QWJ-001-199`）保持原值，不做强制重生成。

### 5.5 校验规则

1. `deviceName` 去空格后不能为空，最大 64 字符。
2. `deviceIp` 必须符合 IPv4 格式。
3. `deviceType` 必须在白名单内。
4. `deviceCode` 只允许字母、数字、`-`、`_`，最大 64 字符。
5. `warrantyExpire` 为空或合法 `YYYY-MM-DD`。
6. `collectType` 强制写 `ping`，`collectPort` 强制写 `0`。
7. 同一网关内 `deviceIp` 允许重复，但页面提示“该 IP 已被其他设备使用”，由操作人确认。
8. 删除设备必须二次确认；删除最后一台设备时要求输入确认短语。

### 5.6 本地页面

在 `/local` 页面增加“设备管理”页签：

1. 列表字段：设备编码、名称、IP、类型、位置、在线状态、最后同步时间。
2. 操作按钮：新增、编辑、删除、立即同步。
3. 新增/编辑使用弹窗，字段与 API 一致。
4. 新增弹窗中设备编码可留空自动生成或手工填写；创建成功后详情页只读。
5. 顶部展示：`revision`、设备总数、最后同步时间、同步状态。
6. 同步失败时展示失败原因和下次重试时间。

## 6. 云端设计

### 6.1 新增同步接口

```http
POST /edge/config/devices/sync
Header: X-Gateway-Token: <网关Token>
Content-Type: application/json
```

请求体：

```json
{
  "gatewayCode": "GW19920261122192456",
  "revision": 12,
  "updatedAt": "2026-11-24 10:00:00",
  "full": true,
  "allowEmpty": false,
  "devices": [
    {
      "deviceCode": "DEV-GW19920261122192456-0001",
      "deviceName": "48盘位磁盘阵列",
      "deviceIp": "192.168.1.55",
      "deviceType": "nvr",
      "location": "监控中心机房",
      "brand": "海康威视",
      "model": "DS-9632",
      "collectType": "ping",
      "collectPort": 0,
      "warrantyExpire": null
    }
  ]
}
```

响应体：

```json
{
  "code": 200,
  "data": {
    "revision": 12,
    "created": 1,
    "updated": 2,
    "disabled": 1,
    "unchanged": 80,
    "syncTime": "2026-11-24 10:00:03"
  }
}
```

### 6.2 鉴权与租户绑定

1. 通过 `X-Gateway-Token` 调用 `EdgeGatewayService.getByToken` 查询网关。
2. Token 无效或网关禁用，返回 `GATEWAY_TOKEN_INVALID`。
3. 从网关记录读取 `gateway_id`、`tenant_id`、`gateway_code`。
4. 请求体 `gatewayCode` 必须与 Token 对应网关一致，不一致返回 403。
5. 忽略请求体中的任何 `tenantId` 字段。
6. 同步写入的 `tenant_id`、`gateway_id` 只能来自 Token 查询结果。

### 6.3 幂等与版本控制

1. 幂等键：`gateway_id + device_code`。
2. 云端 `edge_gateway` 增加 `inventory_revision` 字段，保存最后成功应用的 revision。
3. 请求 `revision < inventory_revision` 时返回 `409 STALE_REVISION`，不应用数据。
4. 请求 `revision == inventory_revision` 时按幂等重放处理，执行 upsert 但不重复插入。
5. 请求 `revision > inventory_revision` 时执行同步，成功后更新 `inventory_revision`、`inventory_synced_at`、`inventory_device_count`。
6. 同步在同一数据库事务中完成，失败整体回滚。
7. `409 STALE_REVISION` 响应携带云端当前 `currentRevision`；本地收到后把本地 revision 提升到 `currentRevision + 1`，更新 `mapping.json` 并重试一次，避免备份恢复导致永久版本冲突。

### 6.4 全量与软删除

1. `full=true` 表示本次是全量清单。
2. 先查询该 `gateway_id` 下所有未删除设备编码。
3. 对清单内设备执行新增或更新。
4. 对“云端存在、本次清单不存在”的设备执行 `del_flag=2`。
5. 软删除设备保留 `device_code`、历史事件和告警记录。
6. 已软删除设备重新出现在清单中时，恢复 `del_flag=0` 并更新配置字段。
7. `devices` 为空且 `allowEmpty=false` 时返回 400，拒绝清空。
8. `allowEmpty=true` 只允许本地管理页面“清空全部”二次确认后触发。

### 6.5 字段映射

| 本地清单字段 | 云端 device 字段 | 说明 |
|---|---|---|
| `deviceCode` | `device_code` | 幂等键之一 |
| `deviceName` | `device_name` | 设备名称 |
| `deviceIp` | `ip_address` | 管理 IP |
| `deviceType` | `device_type` | 设备类型 |
| `location` | `location` | 部署位置 |
| `brand` | `brand` | 品牌 |
| `model` | `model` | 型号 |
| `collectType` | `collect_type` | 固定 `ping` |
| `collectPort` | `collect_port` | 固定 `0` |
| `warrantyExpire` | `warranty_expire` | 保修截止日期 |
| 网关 Token 推导 | `gateway_id` | 网关 ID |
| 网关 Token 推导 | `tenant_id` | 租户 ID |
| 固定 | `source` | 固定 `edge` |
| 同步时间 | `synced_at` | 最后同步时间 |

同步不写入 `status`、`line_status`。

### 6.6 数据库变更

`device` 表新增：

```sql
ALTER TABLE device
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'cloud' COMMENT '来源：edge/cloud',
  ADD COLUMN synced_at DATETIME NULL COMMENT '最后同步时间';
```

`edge_gateway` 表新增：

```sql
ALTER TABLE edge_gateway
  ADD COLUMN inventory_revision BIGINT NOT NULL DEFAULT 0 COMMENT '设备清单版本',
  ADD COLUMN inventory_synced_at DATETIME NULL COMMENT '设备清单最后同步时间',
  ADD COLUMN inventory_device_count INT NOT NULL DEFAULT 0 COMMENT '设备清单数量';
```

索引：

```sql
ALTER TABLE device
  ADD UNIQUE KEY uk_gateway_device (gateway_id, device_code);
```

如果现有唯一索引是 `tenant_id + device_code`，需要先评估并调整为 `gateway_id + device_code`，避免同租户多网关设备编码冲突。

历史数据：

1. 现有设备 `source` 默认 `cloud`。
2. 网关首次同步后，本网关设备变为 `source='edge'`。
3. 其他租户、非网关纳管设备保持 `source='cloud'`。

## 7. 云端只读控制

1. 设备管理接口的新增、编辑、删除一律拒绝，不区分 `source` 或 `gateway_id`。
2. 云端设备管理只读，所有设备统一返回：`设备清单由边缘网关维护，请在网关本地管理页面操作`。
3. 返回统一错误：`设备清单由边缘网关维护，请在网关本地管理页面操作`。
4. 前端设备列表移除新增、编辑、删除按钮，保留查询、详情、二维码、状态和拓扑只读能力。
5. 列表保留“来源（edge/cloud）”和“最后同步时间”两列，便于确认清单是否已同步。
6. 平台超级管理员不保留应用内编辑入口；紧急修正通过数据库运维流程处理。

保留只读接口：

- 设备列表
- 设备详情
- 设备拓扑
- 设备状态
- 设备事件

## 8. 同步引擎

### 8.1 触发方式

1. 定时触发：`@Scheduled(fixedDelay = inventorySyncInterval)`，默认 300000 毫秒，即 5 分钟。
2. 本地增删改成功后立即触发一次。
3. 本地管理页面“立即同步”手动触发。
4. 网关启动后首次同步一次。

### 8.2 同步流程

```text
读取本地 mapping.json 当前快照
   ↓
校验 revision、设备数量、设备编码唯一性
   ↓
构造请求体
   ↓
POST /edge/config/devices/sync
   ↓
HTTP 200 且 code=200
   ├── 记录同步成功
   ├── 更新 runtimeState 同步状态
   └── 更新本地 lastSuccessfulRevision
失败
   ├── 保留本地清单
   ├── 记录错误
   └── 按退避策略重试
```

### 8.3 退避策略

1. 首次失败后 15 秒重试。
2. 连续失败：30 秒、60 秒、120 秒、300 秒。
3. 最大间隔 300 秒，不无限增长。
4. 任一次成功后退避重置。
5. 进程重启后从本地最新清单重新全量推送，不依赖内存队列。

### 8.4 空清单保护

1. `InventorySync` 默认拒绝发送空设备清单。
2. 只有本地页面执行“清空全部”并二次确认后，才允许 `allowEmpty=true`。
3. 云端收到空清单且 `allowEmpty=false` 时返回 400。

### 8.5 同步状态

本地 `RuntimeState` 增加：

- `lastInventorySyncAt`
- `lastInventorySyncResult`
- `inventoryRevision`
- `inventoryDeviceCount`
- `inventorySyncFailCount`
- `nextInventoryRetryAt`

网关心跳增加：

- `inventoryRevision`
- `inventoryDeviceCount`
- `lastInventorySyncAt`
- `inventorySyncStatus`

云端 `edge_gateway` 保存最后同步时间与设备数量，用于平台侧判断同步是否停滞。

## 9. 告警链路保障

### 9.1 录入后立即生效

1. 本地保存设备后立即重新生成 `ping.json`。
2. 立即触发一次云端清单同步。
3. 不等待 5 分钟定时周期。

### 9.2 状态上报与清单同步解耦

1. `SnapshotCollector` 继续按 30 秒上报设备状态。
2. 云端 `/edge/report/status` 遇到未知设备时只记录日志，不自动创建设备。
3. 清单同步成功后，下一轮状态上报自然更新设备状态。

### 9.3 事件不丢弃

1. `/alert/push` 收到未知设备编码时，仍然保存事件。
2. 事件保留 `device_code`、`device_name`、`device_ip`、`gateway_id`。
3. 清单同步完成后，设备详情页可以按 `device_code` 关联展示历史事件。
4. 不允许因为云端暂时没有设备记录而直接丢弃告警。

### 9.4 同步停滞告警

1. 云端检查 `edge_gateway.inventory_synced_at`。
2. 超过 3 个同步周期未更新，产生“网关清单同步异常”平台告警。
3. 该告警与设备离线告警分开，避免把“清单没同步”误判成“设备离线”。

## 10. 异常处理

| 场景 | 本地行为 | 云端行为 |
|---|---|---|
| 云端不可达 | 本地保存成功，进入重试 | 无请求 |
| Token 无效 | 记录失败，继续重试 | 返回 401/业务错误 |
| 网关编码不一致 | 记录失败，不重试直到配置修复 | 返回 403 |
| revision 过旧 | 记录失败，提示版本冲突 | 返回 409，不应用 |
| 空清单 | 默认拒绝发送 | `allowEmpty=false` 返回 400 |
| 设备编码重复 | 本地保存失败 | 不接收 |
| IP 格式错误 | 本地保存失败 | 不接收 |
| 云端事务失败 | 记录失败并重试 | 整体回滚 |
| 本地文件损坏 | 启动失败并保留备份 | 无请求 |

## 11. 安全

1. 同步接口只接受网关 Token，不接受用户 JWT。
2. Token 必须对应启用状态的网关。
3. 云端根据 Token 推导租户，禁止请求体覆盖租户。
4. 请求体限制设备数量，建议单次最多 5000 台。
5. 记录同步审计日志：网关、revision、设备数量、新增/更新/删除数量、结果。
6. 本地管理接口继续受 `LocalAuthFilter` 保护，未登录返回 401。
7. 本地删除、清空操作要求二次确认。

## 12. 测试计划

### 12.1 单元测试

1. `LocalDeviceService` 新增、编辑、删除、编码生成、校验、revision 递增。
2. `mapping.json` 原子写入与备份。
3. `MappingSync` 本地加载并生成 `ping.json`。
4. `InventorySync` 请求体构造、空清单保护、退避策略。
5. 云端 revision 比较、幂等 upsert、软删除、恢复软删除设备。
6. Token 与 `gatewayCode` 不一致返回 403。

### 12.2 集成测试

1. 本地新增设备 → 云端设备数 +1 → `ping.json` 出现新 IP。
2. 本地编辑 IP → 云端 IP 更新 → Prometheus target 更新。
3. 本地删除设备 → 云端 `del_flag=2` → `ping.json` 移除。
4. 云端尝试编辑任意设备 → 返回拒绝。
5. 云端断网 → 本地继续增删改 → 恢复后自动补推。
6. 重复推送同一 revision → 云端不重复插入。
7. 旧 revision 推送 → 云端拒绝。
8. 空清单推送 → 云端拒绝。
9. 告警先于清单同步到达 → 事件保留，不丢弃。
10. 同租户两个网关各自同步 → 设备互不覆盖。

### 12.3 验收清单

- [ ] 本地页面可以新增、编辑、删除设备。
- [ ] 保存后 `mapping.json` 和 `ping.json` 立即更新。
- [ ] 保存后云端在 1 个同步周期内出现对应设备。
- [ ] 云端设备数量、IP、名称、位置与本地一致。
- [ ] 云端租户侧无法编辑网关设备。
- [ ] 删除设备后云端软删除，历史事件仍可查。
- [ ] 云端不可达时本地操作不失败，恢复后自动补推。
- [ ] 重复同步不产生重复设备。
- [ ] 新增设备后能正常产生 ping 状态和离线告警。
- [ ] 同步停滞能被平台告警发现。

## 13. 部署与回滚

### 13.1 部署顺序

1. 云端执行数据库变更，新增字段和索引。
2. 部署云端 `netsight-server`，上线同步接口和只读控制。
3. 部署网关 `nams-agent`，切换为本地清单权威源。
4. 用 `.199` 现有 84 台设备做首次全量同步。
5. 验证云端数量、字段、来源、同步时间。
6. 打开云端设备只读页面。
7. 观察一个完整同步周期和一轮状态上报。

### 13.2 回滚

1. 云端字段为新增字段，回滚应用不需要删列。
2. 网关保留旧版 jar 和 `mapping.json` 备份。
3. 旧版 `GET /edge/config/mapping` 接口保留只读兼容，回滚网关后可继续拉取。
4. 同步失败不影响本地 ping 采集和本地页面。

## 14. 兼容与后续

1. 本期保留云端 `GET /edge/config/mapping` 只读接口，兼容尚未升级的网关。
2. 所有网关升级完成后，可评估下线该接口。
3. 后续如设备量增长，可在全量快照基础上增加增量事件同步。
4. 本期不做 CSV/Excel 批量导入，后续可单独设计。
5. 本期不做云端应急编辑入口，紧急修正走数据库运维流程。

## 15. 已确认决策

1. 本地录入形式：网关本地管理页面新增设备增删改查。
2. 云端权限：租户侧完全只读，不允许新增、编辑、删除设备。
3. 同步周期：默认 5 分钟，本地变更后立即同步。
4. 设备编码：创建时允许手工填写或留空自动生成，格式 `DEV-<gatewayCode>-<4位序号>`；创建成功后不可修改，历史编码保持原值。
5. 平台超级管理员不保留应用内编辑入口。
