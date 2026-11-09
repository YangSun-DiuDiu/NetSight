# AI 诊断 P0 生产化改造清单（测试环境先行）

> 范围：H5 维修端工单详情页「AI 诊断」→ `POST /ai/knowledge/ask`。
> 环境：仅测试环境 `115.159.194.149`；本轮不触碰生产 `1.94.26.126` / `netsight-producton`。
> 目标：先让维修员在测试环境“能用、可控、可审计、可回滚”，再按同一套 SQL/配置推广到生产。

## 1. 改造边界

**做**：
- 修权限错配：维修员不再依赖 `knowledge:fault:view`，新增专用 `ai:diagnose`。
- 修超时错配：前端 30s、后端 connect 3s + read 15s，默认不重试；并发舱壁 + 熔断。
- 补成本兜底：租户日调用上限，超限回知识库原文。
- 补脱敏与审计：默认开启脱敏；`ai_usage_log` 增加 user/order/request/latency/prompt 版本。
- 修检索退化：关键词为空直接 `noHit`，不再返回“浏览量 topK”；按 `deviceType` 软过滤。
- 补输入边界与提示注入防护、H5 免责声明。
- 测试租户 7 补一份知识库种子，验证链路。

**不做**：
- 不引入向量检索/IK 分词/rerank（P1，先保证 P0 闭环）。
- 不做租户级费用看板/配额管理后台（P1）。
- 不改知识库前端页面与权限菜单结构（只加一个 API 权限节点）。
- 不动生产环境、不改生产库。

## 2. 文件级改造清单

| 文件 | 改动 | 目的 |
|---|---|---|
| `netsight-server/src/main/java/com/netsight/modules/ai/controller/AiController.java` | `@PreAuthorize` 增加 `hasAuthority('ai:diagnose')`，保留 `knowledge:fault:view` 兼容 | 维修员可调用；管理员旧权限不失效 |
| `netsight-server/src/main/java/com/netsight/modules/ai/dto/AiAskRequest.java` | 增加 `orderId` | 审计关联工单 |
| `netsight-server/src/main/java/com/netsight/modules/ai/dto/AIRequest.java` | 增加 `userId/orderId/requestId/promptVersion` | 贯穿到模型适配器审计 |
| `netsight-server/src/main/java/com/netsight/modules/ai/entity/AIUsageLog.java` | 增加 `userId/orderId/requestId/latencyMs/promptVersion` | 审计字段落库 |
| `netsight-server/src/main/java/com/netsight/modules/ai/KnowledgeAskService.java` | 缓存读写 try/catch；缓存 key 改 SHA-256 且含设备上下文；关键词为空即 `noHit`；按 `deviceType` 软过滤；租户日额度；问题/上下文/知识库字段截断；服务层设备名/位置脱敏；prompt 注入防护；纯文本输出 | 解决缓存故障、检索退化、成本、脱敏、注入、展示不一致 |
| `netsight-server/src/main/java/com/netsight/modules/ai/CloudModelClient.java` | 超时默认 3s/15s、重试 0；并发 Semaphore；连续失败熔断 60s；审计补字段与 latency；脱敏覆盖全部 IPv4/手机号/身份证/中文设备名 | 避免 108s 长尾、雪崩、审计不足、数据出域 |
| `netsight-server/src/main/resources/application.yml` | `netsight.ai.*` 默认值与环境变量：`AI_CONNECT_TIMEOUT_MS=3000`、`AI_READ_TIMEOUT_MS=15000`、`AI_MAX_RETRIES=0`、`AI_MAX_CONCURRENT=10`、`AI_FAILURE_THRESHOLD=5`、`AI_OPEN_SECONDS=60`、`AI_DAILY_CALL_LIMIT=200`、`AI_MASK_SENSITIVE=true` | 配置化，生产可按环境覆盖 |
| `netsight-ui/src/views/m/order-detail.vue` | 请求单独 `timeout:30000`；body 增加 `orderId`；按钮按 loading/空问题禁用；403/超时错误提示；免责声明 | 前端不再 10s 误杀；提示可定位；合规提示 |
| `docs/sql/ai_p0_migration.sql` | 新增审计字段、`ai:diagnose` 权限、租户 7 知识库种子 | DB 侧一次性迁移 |
| `docs/sql/ai_p0_data.sql` | 权限与种子数据（可重复执行，不含 ALTER） | 结构已迁移时补数据 |
| `deploy/apply_ai_p0_115.py` | 备份相关表 → 执行迁移/补数据 → 回读 → 更新 `nams.env` | 测试环境可重复执行 |
| `deploy/deploy_ai_p0_115.py` | MD5 双端校验 → 备份 → 停旧 → 换 jar → 启新 → 健康检查 → 换 dist | 一键发布与回滚点 |
| `deploy/verify_ai_p0_115.py` | 结构/权限/配置/产物/健康/真实问诊验收 | 可重复验收，不靠人工记忆 |

## 3. SQL 迁移

文件：`docs/sql/ai_p0_migration.sql`

包含：
1. `ai_usage_log` 增加 `user_id/order_id/request_id/latency_ms/prompt_version` + 索引。
2. `sys_permission` 新增 `ai:diagnose`（`perm_type=api`，不生成菜单），并 `INSERT IGNORE` 授给 `repairer`（role_id=4）。
3. 测试环境把租户 1 的 17 条启用知识库复制给租户 7（表内共 18 条，其中 1 条 `del_flag=2`；按 title 去重）。

执行前备份：
```bash
mysqldump -usadmin -p netsight ai_usage_log fault_article sys_permission sys_role_permission > /opt/nams-server/backup-ai-p0-$(date +%Y%m%d-%H%M%S).sql
```

> `ALTER TABLE` 是一次性迁移，不可重复执行；权限与种子可重复执行。

## 4. 测试环境配置

在 `/opt/nams-server/nams.env` 显式增加（Key 继续沿用现有 `DEEPSEEK_API_KEY`，不打印、不入库）：

```bash
AI_MASK_SENSITIVE=true
AI_CONNECT_TIMEOUT_MS=3000
AI_READ_TIMEOUT_MS=15000
AI_MAX_RETRIES=0
AI_MAX_CONCURRENT=10
AI_FAILURE_THRESHOLD=5
AI_OPEN_SECONDS=60
AI_DAILY_CALL_LIMIT=200
```

重启必须走 `/opt/nams-server/start.sh`（加载 `nams.env`，避免 JWT_SECRET 为空启动失败）。

## 5. 验收清单

运行：
```bash
python deploy/verify_ai_p0_115.py            # 含一次真实问诊
python deploy/verify_ai_p0_115.py --no-e2e   # 只做结构检查
```

必须全部通过：
- [x] `ai_usage_log` 5 个新字段存在。
- [x] `ai:diagnose` 节点存在且 role_id=4 已授权。
- [x] 租户 7 知识库条目 > 0（实测 17 条启用）。
- [x] `nams.env` 已显式设置脱敏/重试/读超时。
- [x] 线上 jar 含 `AiController.class`。
- [x] 前端产物含「AI 建议仅供参考」「AI 响应超时」。
- [x] `/actuator/health` 200。
- [x] H5 维修员登录成功，`POST /ai/knowledge/ask` 返回 200、有 answer、非 noHit。
- [x] 最新 `ai_usage_log` 含 `request_id` 与 `knowledge_ask_v1`。
- [x] 重复同一问题第二次返回 `fromCache=true`（`deploy/check_ai_cache_115.py` 实测通过）。

## 6. 生产落地顺序

1. **先备份**生产库 `ai_usage_log/fault_article/sys_permission/sys_role_permission`。
2. 执行 `docs/sql/ai_p0_migration.sql`（生产把 `@seed_tenant` 改为实际租户；知识库内容由运营确认，不要直接复制测试数据）。
3. 生产 `nams.env` 配 Key、`AI_MASK_SENSITIVE=true`、超时/重试/日额度；先 `AI_ENABLED=false`。
4. 发布后端 jar + 前端 dist（Nginx 不改）。
5. 单租户灰度：`AI_ENABLED=true`，只给该租户维修员授 `ai:diagnose`；观察 1–2 周成功率、P95 耗时、日费用、降级率。
6. 全量开量；保留 `AI_ENABLED=false` 作为一键回滚。
7. 回滚：`AI_ENABLED=false` 重启即可停止模型调用；代码/SQL 回滚按备份恢复。

## 7. 仍需生产确认 / 不在本轮闭环

- 生产是否已注入 `DEEPSEEK_API_KEY`、`ai_usage_log` 是否已建、出站 443 是否放通：本轮未触碰生产，需上线前核查。
- 生产租户知识库谁提供、每租户多少条、是否允许复制测试内容：需业务确认。
- 是否允许工单/设备/位置/客户描述出域到 DeepSeek 公有云：需合同/合规确认；不允许则改私有化模型或只发脱敏结构化摘要。
- 租户日额度 200 次是 P0 兜底值，需按实际调用量与预算调整。
- 生成式 AI 内容标识、算法备案/安全评估、内容安全审核：需合规评估。
- 检索仍为 `LIKE`，P1 再上 IK/全文索引/向量+rerank。
- 当前无 AI 单测；建议后续用 WireMock 覆盖成功/超时/5xx/空 choices/usage 缺失/429/跨租户隔离/降级。

## 8. 本轮执行结果（2026-11-10 05:21 CST，测试环境 115）

**构建**
- 后端：`mvn clean package -DskipTests` 成功（187 源文件，`target/netsight-server.jar` 110,603,389 B，MD5 `60b51276147b293a7c804c2652165a3b`）。
- 前端：`npm run build:prod` 成功（9 个历史 warning，非本次引入；`chunk-acee6814.b4b44965.js` 同时含免责/超时文案）。

**SQL / 配置**
- 执行前备份：`/opt/nams-server/ai-p0-backup-20261110-052046.sql`（37,330 B；`mysqldump` 报 PROCESS 权限 warning，表数据已导出）。
- `ai_usage_log` 新增 5 字段 + 索引；`ai:diagnose` 权限 id=159，已授 `repairer(role_id=4)`；租户 7 复制 17 条启用知识库。
- `/opt/nams-server/nams.env` 备份 `nams.env.bak-20261110-052046` 后显式写入 8 个 AI 配置：`AI_MASK_SENSITIVE=true`、`AI_CONNECT_TIMEOUT_MS=3000`、`AI_READ_TIMEOUT_MS=15000`、`AI_MAX_RETRIES=0`、`AI_MAX_CONCURRENT=10`、`AI_FAILURE_THRESHOLD=5`、`AI_OPEN_SECONDS=60`、`AI_DAILY_CALL_LIMIT=200`；`DEEPSEEK_API_KEY` 原值未改、未打印。

**部署**
- 后端：`/opt/nams-server/netsight-server.jar`（MD5 双端一致）；旧 jar 备份 `/opt/nams-server/netsight-server.jar.bak-ai-p0-20261110-052101`；新 PID 1452675；`/actuator/health` 200。
- 前端：`/opt/nams-ui/dist`（tar 3,609,519 B，MD5 `e3af8283d2641627bf14d5a14e2e5f48`）；旧 dist 备份 `/opt/nams-ui/dist.bak-ai-p0-20261110-052101`。

**验收（`verify_ai_p0_115.py` 全部 PASS）**
- 结构/权限/配置/产物/健康全部通过。
- H5 维修员 `15657477316` 短信登录成功；`POST /prod-api/ai/knowledge/ask` 返回 200、`aiUsed=true`、`noHit=false`、`sources=1`。
- `ai_usage_log` 最新行：`tenant=7 / user=5 / order=0 / requestId=0a7998b7-... / latency=2118ms / prompt_version=knowledge_ask_v1 / success`。
- 缓存复测：同一问题第 1 次 `fromCache=false`，第 2 次 `fromCache=true`（`deploy/check_ai_cache_115.py`）。
- 运行日志：`app.log` 最近 AI 调用均为 `success`，无 `ERROR/Exception`。

**未做 / 未验证**
- 未触碰生产 `1.94.26.126` / `netsight-producton`。
- 未做浏览器目视验收（H5 页面按钮/免责声明为构建产物字符串核验 + API E2E，未截图）。
- 未提交 git；未推送远端。
- 未覆盖模型超时/5xx/429/熔断/额度耗尽等故障注入场景；这些需 P1 WireMock/混沌测试。
