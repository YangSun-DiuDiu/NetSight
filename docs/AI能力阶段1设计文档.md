# NetSight AI 能力接入设计文档（阶段 1）

> 版本：V1.0 ｜ 日期：2026-10-11 ｜ 状态：待评审
> 范围：阶段 1 —— 告警智能摘要 + 告警风暴抑制/根因关联 + 知识库 AI 问诊（RAG）
> 模型：DeepSeek（云端 API，.55 出网已验证可达：api.deepseek.com 401=服务正常）

---

## 1. 概述

### 1.1 背景

NetSight 目前没有任何 AI 能力（仅首页有"AI 实时分析"文案占位）。系统已具备：完整的告警中心（event_record 事件中心）、知识库（fault_article 100+ 篇）、设备拓扑（device_topology）、多租户体系。这些数据天然可作为 AI 能力的语料与场景。

### 1.2 阶段 1 目标

| 能力 | 一句话描述 | 价值 |
|---|---|---|
| 告警智能摘要 | 每条告警入库后异步生成一句话"人话"说明 | 一线不再读 Prometheus 原始告警 |
| 告警风暴抑制 | 5 分钟内同设备同类告警自动收敛计数 | 防凌晨抖动刷屏 |
| 根因关联 | 拓扑上游离线→下游告警自动标记"跟随" | 告警瀑布里定位根因 |
| 知识库 AI 问诊 | 维修端/PC 提问→检索知识库→AI 生成排查步骤 | 让 100+ 篇知识真正用起来 |

### 1.3 非目标（阶段 1 不做）

- 工单智能派单、设备健康度预测、备件预测（阶段 2/3）
- 租户级独立 API Key（阶段 1 平台级统一 Key，租户级放阶段 2）
- 向量数据库（阶段 1 知识量小，用关键词检索足够）
- 边缘网关侧轻量模型（远期）

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      前端（PC Vue2 / H5 维修端）              │
│   事件列表·详情（AI摘要）   知识库·AI问诊对话框    LIVE列表   │
└────────────────────────────┬────────────────────────────────┘
                             │ REST
┌────────────────────────────▼────────────────────────────────┐
│                   AI 应用层（每个能力一个 Service）           │
│  AlertSummaryService   StormSuppressService   RootCauseService│
│  KnowledgeAskService（RAG：检索→拼prompt→生成）             │
└────────────────────────────┬────────────────────────────────┘
                             │ 调用 AIService 接口
┌────────────────────────────▼────────────────────────────────┐
│                    AI 服务层（与通知通道同构）                │
│  AIService（接口/SPI）→ CloudModelClient（DeepSeek HTTP）    │
│  多租户隔离 · @RateLimit 限流 · 超时降级 · 脱敏开关 · usage日志│
└────────────────────────────┬────────────────────────────────┘
                             │ HTTPS（Hutool HttpUtil，与 Pushplus 同风格）
┌────────────────────────────▼────────────────────────────────┐
│              模型供应商：DeepSeek（可切换豆包/通义）           │
└─────────────────────────────────────────────────────────────┘
```

设计原则：**AI 能力与模型解耦**——`AIService` 接口定义能力（摘要/问答/补全），`CloudModelClient` 只负责把请求发给某个厂商。换厂商/换私有化部署只改实现类，业务代码不动。与 `NotificationChannelSender` 的设计哲学一致。

---

## 3. 技术选型

| 项 | 选型 | 理由 |
|---|---|---|
| 模型 | DeepSeek（deepseek-chat） | .55 已验证可达、中文强、价格最低、OpenAI 兼容格式 |
| HTTP 客户端 | Hutool `HttpUtil` | 与 PushplusChannelSender 同款，项目已有依赖，风格统一 |
| JSON | Hutool `JSONUtil` | 同上 |
| 异步 | Spring `@Async` + 独立线程池（ai-executor） | 摘要生成不能阻塞告警主链路 |
| 缓存/状态 | Redis | 风暴窗口计数、结果缓存（问答同问题 10 分钟缓存） |
| 限流 | 复用现有 `@RateLimit` 切面 | 已有能力，防成本失控 |
| 配置 | application.yml `netsight.ai.*` + 环境变量 | 与 jwt/aes/sms 同模式，Key 不入库不入 git |

---

## 4. 配置设计（application.yml 新增）

```yaml
# NetSight AI 能力（阶段 1）
netsight:
  ai:
    # 总开关：false 时所有 AI 能力自动降级为规则/原文（不调用模型）
    enabled: ${AI_ENABLED:true}
    # DeepSeek API（Key 走环境变量，禁止硬编码入库；默认空=禁用，启动不校验避免阻塞非 AI 环境）
    provider: deepseek
    api-key: ${DEEPSEEK_API_KEY:}
    api-url: ${DEEPSEEK_API_URL:https://api.deepseek.com/chat/completions}
    model: ${DEEPSEEK_MODEL:deepseek-chat}
    # 超时与重试
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
    max-retries: 2
    # 异步摘要线程池
    async-pool-size: 4
    # 数据脱敏：true=设备名/IP 出域前打码（生产建议开）
    mask-sensitive: ${AI_MASK_SENSITIVE:false}
    # RAG 检索条数与缓存
    knowledge-topk: 5
    answer-cache-minutes: 10
```

> **安全说明**：`DEEPSEEK_API_KEY` 通过 systemd 环境变量注入（与 `JWT_SECRET`/`AES_TOKEN_KEY`/`WEBHOOK_TOKEN` 同模式），不写入 application.yml 明文、不提交 git、不进操作日志（OperLogAspect 脱敏正则已覆盖 `secret/token` 类字段，新 Key 走环境变量天然豁免）。

---

## 5. 模块设计

### 5.1 AI 服务层（新包 `com.netsight.modules.ai`）

**AIService（接口）** —— 能力定义：

```java
public interface AIService {
    /** 单轮补全（摘要/问答统一入口），返回模型文本；失败抛 AIServiceException */
    String complete(AIRequest request);
    /** 是否可用（开关+Key 是否配置） */
    boolean enabled();
}

/** 请求：prompt 体系（system+user）、温度、最大 token、租户、是否脱敏 */
@Data
public class AIRequest {
    private Long tenantId;      // 多租户隔离/审计
    private String systemPrompt;
    private String userPrompt;
    private Double temperature; // 默认 0.3（运维场景要稳定不要发散）
    private Integer maxTokens;  // 默认 500
    private String feature;     // alert_summary / knowledge_ask / storm_merge
}
```

**CloudModelClient（实现）** —— DeepSeek 适配：

- `POST {api-url}`，Body：`{model, messages:[{role:system},{role:user}], temperature, max_tokens}`，Header：`Authorization: Bearer <key>`
- 用 Hutool `HttpUtil.createPost(url).header(...).body(...).timeout(...).execute()`，`JSONUtil` 解析 `choices[0].message.content`、`usage`
- **超时/失败策略**：connect 5s / read 30s；失败重试 2 次（指数退避 1s/2s）；仍失败抛异常 → 应用层降级
- **脱敏钩子**：`maskSensitive(text)` —— 开关开启时对设备名/IP/位置打码（正则 `\b(?:192\.168\.\d+\.\d+|[\u4e00-\u9fa5]{2,8}(?:摄像头|主机|交换机|服务器|网关))\b` → `***`）

**AIUsageLog**（审计/成本，表 `ai_usage_log`）：

| 字段 | 说明 |
|---|---|
| id / tenant_id | 主键/租户 |
| feature | alert_summary / knowledge_ask / storm_merge |
| prompt_tokens / completion_tokens | 模型用量 |
| cost_yuan | 按 DeepSeek 单价估算（deepseek-chat 输入¥0.5/M、输出¥2/M，可配置） |
| model | 实际模型名 |
| status | success / timeout / failed |
| error_msg | 失败原因（截断 200） |
| create_time | 时间 |

### 5.2 能力 1：告警智能摘要（AlertSummaryService）

**触发点**：`EventCenterService.receiveEvent()` 事件入库后（`eventMapper.insert` 之后、`dispatch` 之前或之后异步）。

**流程**：
1. 构造 prompt：把 `eventType/severity/deviceName/deviceIp/deviceType/location/labelsJson`（脱敏后）给模型，要求输出 **≤50 字的一句话**（如："门卫室大华摄像头网络中断（192.168.1.77），属视频监控设备，建议优先检查交换机端口与网线"）
2. `@Async("aiExecutor")` 调用 `AIService.complete()`，成功 → `UPDATE event_record SET ai_summary=? WHERE id=?`
3. **降级**：模型不可用/超时 → 用规则模板生成兜底摘要（"设备离线：门卫室大华摄像头"），`ai_summary` 仍回填，`ai_usage_log.status=failed` 记一次
4. 手动重试：`POST /ai/event/{id}/summarize`（管理员可对空摘要补生成）

**展示**：事件列表新增"AI 摘要"列（空值显示 `--`）；事件详情展示；LIVE 列表 hover tooltip 显示。

### 5.3 能力 2：告警风暴抑制 + 根因关联（StormSuppressService / RootCauseService）

**纯规则算法，不调用模型**（零成本、确定性强，阶段 1 不引入 LLM 聚类）：

**风暴抑制（StormSuppressService）**：
- Redis key：`ai:storm:{tenantId}:{deviceId}:{eventType}`，窗口 5 分钟，自增计数
- 计数=1（首条）→ 正常记录；计数≥2 → 该条事件 `merged_count=计数` 标记收敛，`ai_summary` 前缀显示"【已收敛 N 条同类告警】"
- 窗口过期自动消失；不删原始记录（审计保留），只做标记

**根因关联（RootCauseService）**：
- 数据源：`device_topology`（已有上下游）+ 时间窗
- 规则：上游设备（parent）离线事件后 **10 分钟内**，下游（child）产生离线/链路异常事件 → 下游事件打 `root_cause=parentId`，`ai_summary` 标注"【跟随】可能因上级 X 离线导致"
- 实现：Redis 记上游离线时间戳（`ai:root:{tenantId}:{parentId}`），下游事件入库时查
- 拓扑删除/变更时清理对应 Redis key（DeviceService.saveTopology/deleteDevice 里补清理）

**表变更（event_record 加 3 列，SQL 见附录 A）**：

| 列 | 类型 | 说明 |
|---|---|---|
| ai_summary | VARCHAR(500) NULL | AI/规则摘要 |
| merged_count | INT NOT NULL DEFAULT 0 | 风暴收敛计数（0=未收敛） |
| root_cause | VARCHAR(64) NULL | 根因关联：上级设备 ID（空=无） |

### 5.4 能力 3：知识库 AI 问诊（KnowledgeAskService，RAG）

**数据源**：`fault_article`（title/symptom/cause/solution/keywords，多租户）

**流程（先检索后生成，不把全库塞给模型）**：
1. **检索**：用户问题分词（简单按空格/关键词切分 + 去掉"怎么/如何/处理/解决"等停用词）→ 与 `keywords LIKE` + `title/symptom LIKE` 匹配，按命中度排序取 `topK=5` 篇**当前租户**文章
2. **拼 prompt**：
   ```
   system: 你是 NetSight 运维专家。基于以下知识库条目回答问题，只引用给出的资料，
           不编造。输出格式：可能原因 → 排查步骤（编号列表）→ 参考文章标题。
   user: 问题：{question}\n\n知识库条目：{top5 文章拼接}
   ```
3. **生成**：调用 `AIService.complete()` → 返回答案 + `sources`（引用的文章标题列表）
4. **缓存**：同租户同问题 10 分钟内命中 Redis 缓存直接返回（省成本）
5. **降级**：模型失败 → 直接返回 top1 文章的 symptom/cause/solution 原文 + 提示"AI 服务暂不可用，以下为知识库原文"
6. **无命中**：检索不到文章 → 明确提示"知识库暂无相关条目"，不空跑模型

**接口**：`POST /ai/knowledge/ask`，Body `{question, topK?}` → `{answer, sources:[{title,category}], fromCache, aiUsed}`

**前端**：
- PC 知识中心-故障库：右上角"AI 问诊"按钮 → 对话框（问题输入 + 答案展示 + 参考文章可点开）
- H5 维修端：工单详情/首页加"AI 问诊"入口，同一接口

---

## 6. API 汇总（阶段 1 新增）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | /ai/knowledge/ask | 登录用户（租户隔离） | 知识库问答 |
| POST | /ai/event/{id}/summarize | 管理员 | 手动补生成摘要 |
| GET | /ai/config | 管理员 | 查看 AI 配置状态（enabled/model/脱敏/用量概览） |

告警摘要/风暴/根因为后台自动运行，无独立接口。

---

## 7. 多租户与安全

- **隔离**：RAG 检索强制 `tenant_id` 过滤；风暴/根因 Redis key 带 tenantId；usage_log 带 tenantId
- **Key 管理**：平台级 Key 走环境变量；不落库、不进日志（脱敏正则兜底）
- **数据出域**：`mask-sensitive` 开关，生产建议开启（设备名/IP 打码后再发模型）
- **成本控制**：`@RateLimit`（如知识问答 10 次/分钟/租户）+ usage_log 成本审计 + 问答缓存
- **可用性**：AI 全部异步 + 降级路径（摘要→规则模板、问答→原文），**模型故障绝不影响告警通知主链路**
- **权限**：知识问答沿用各角色已有菜单权限；AI 配置仅 super_admin

---

## 8. 前端改动点

| 页面 | 改动 |
|---|---|
| 告警中心-事件记录 | 列表加"AI 摘要"列；详情显示摘要/收敛计数/根因 |
| 监控大屏 LIVE | hover 显示 AI 摘要（demo 模式不调模型，直接展示模拟摘要） |
| 知识中心-故障库 | 右上角"AI 问诊"入口 + 问答对话框 |
| H5 维修端 | 增加"AI 问诊"入口（与 PC 同一接口） |
| 系统管理（超管） | AI 配置查看页（enabled/model/脱敏开关/用量）——阶段 1 只读展示 |

---

## 9. 测试方案

**单元**：
- CloudModelClient：请求体构造、Bearer 头、响应解析、超时/重试、脱敏正则
- Prompt 构造：摘要/问答模板、无命中分支、停用词过滤

**集成（.55 真机）**：
- DeepSeek 真实调用：`ai_usage_log` 有记录、返回非空（**验证前提：用户 Key 有效**）
- 摘要：向 /alert/push 推一条告警 → 事件入库 → 异步摘要回填 ≤10s → 列表可见
- 风暴：同设备同类型 5 分钟内连推 5 条 → merged_count=5、摘要带"已收敛"
- 根因：拓扑上游离线 → 10 分钟内推下游离线 → root_cause 标记
- 问答：问"摄像头离线怎么处理"→ 返回答案+引用文章；问无关问题 → 无命中提示
- **多租户**：租户 A 问答不得返回租户 B 文章（隔离断言）
- **四角色**：super_admin/tenant_admin/ops/repairer 按权限访问 /ai 接口
- **降级**：临时置 AI_ENABLED=false → 摘要走规则模板、问答走原文，告警通知链路不受影响

**回归**：事件中心全链路（webhook→路由→模板→发送日志）、知识库 CRUD、驾驶舱

---

## 10. 部署与回滚

- **部署**：mvn package（jar 110MB 级）→ .55 覆盖 `/opt/nams-server/netsight-server.jar` + `systemctl restart`；前端 build:prod → `/opt/nams-ui/dist`；**.55 环境变量追加 `DEEPSEEK_API_KEY`**（systemd drop-in，不动主 unit）
- **DB 变更**：附录 A SQL（event_record 3 列 + ai_usage_log 建表 + 索引），执行前备份
- **回滚**：AI_ENABLED=false 即全功能降级（无需回滚代码）；DB 列可留（不影响既有逻辑）；jar 备份 `.bak_<ts>` 恢复

---

## 11. 阶段 2/3 展望（不在本期范围）

- **阶段 2**：工单智能派单（维修人员技能/在岗/负载画像）、维修记录自动沉淀知识库、租户级 API Key、保修期智能预警
- **阶段 3**：设备健康度预测（Prometheus 指标时序 → 故障概率）、备件需求预测、边缘网关轻量时序异常检测（云边协同）
- 架构预留：AIService 接口不随阶段变化，各阶段只加应用层 Service

---

## 附录 A：数据库变更 SQL（待评审后执行）

```sql
-- event_record 增加 AI 相关列
ALTER TABLE event_record
  ADD COLUMN ai_summary   VARCHAR(500)  NULL COMMENT 'AI/规则生成的告警摘要',
  ADD COLUMN merged_count INT NOT NULL DEFAULT 0 COMMENT '风暴收敛计数(0=未收敛)',
  ADD COLUMN root_cause   VARCHAR(64)   NULL COMMENT '根因关联上级设备ID(空=无)';

-- AI 用量审计表
CREATE TABLE ai_usage_log (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id       BIGINT       NOT NULL COMMENT '租户ID',
  feature         VARCHAR(32)  NOT NULL COMMENT 'alert_summary/knowledge_ask/storm_merge',
  model           VARCHAR(64)  NULL COMMENT '模型名',
  prompt_tokens   INT          NULL COMMENT '输入tokens',
  completion_tokens INT        NULL COMMENT '输出tokens',
  cost_yuan       DECIMAL(10,6) NULL COMMENT '估算费用',
  status          VARCHAR(16)  NOT NULL COMMENT 'success/timeout/failed',
  error_msg       VARCHAR(200) NULL COMMENT '失败原因',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ai_log_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI调用用量审计';
```

## 附录 B：待用户确认项

1. ✅ 云端 API / DeepSeek / Key 已提供（`sk-ddf0...`）——**Key 仅走环境变量，不写入本文档/代码/git**
2. 告警摘要是否展示为事件列表新增列（默认"是"，可改详情页展示）
3. AI 问诊入口：PC 故障库 + H5 维修端均加（默认"是"）
4. 生产脱敏开关 `mask-sensitive` 是否默认开启（建议开启）
