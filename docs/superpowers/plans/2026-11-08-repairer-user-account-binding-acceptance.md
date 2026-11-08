# 维修人员账号绑定改造 — 测试环境验收记录

- 执行时间：2026-11-08 12:13 ~ 12:40 (Asia/Shanghai)
- 测试环境：115.159.194.149（仅测试环境，未触碰生产 1.94.26.126）
- 执行人：自动验收脚本（经 SSH/paramiko 执行，PC/H5 接口 + SQL 双重取证）
- 分支：feature/repairer-user-account-binding（HEAD 96308e0）

## 1. 测试环境版本

| 项 | 值 |
|---|---|
| 后端 jar | /opt/nams-server/netsight-server.jar（本次构建 2026-11-08 12:17:20，110,598,525 字节） |
| 后端构建 | `mvn clean package -DskipTests`，单测 26 项全部通过 |
| 前端 dist | /opt/nams-ui/dist（本次构建 2026-11-08 12:21:22，index.html 7928 字节） |
| 重启脚本 | /opt/nams-server/start.sh（服务器无 start_nams.sh，按探测结果使用 start.sh） |
| 启动结果 | Started NetsightApplication in 11.473s，无 WeakKeyException / BeanCreationException |
| 升级 SQL | netsight-upgrade-v1.3.1-repairer-user-binding.sql 已执行 |

## 2. 升级前/后库结构

升级前：repairer 无 user_id、repairer_type 列，无 uk_repairer_user、idx_repairer_phone。
升级后（DESC / SHOW INDEX）：
- user_id bigint NULL
- repairer_type tinyint NOT NULL DEFAULT 0
- uk_repairer_user (tenant_id, user_id) 唯一索引
- idx_repairer_phone (tenant_id, phone) 普通索引

## 3. 20 项验收矩阵结果

| # | 场景 | 结果 | 关键证据 |
|---|------|------|---------|
| 1 | 内聘绑定成功 | 通过 | 存量档案 id=13 经绑定接口匹配手机号，user_id=22、repairer_type=1，姓名=维修员甲，H5 可登录 |
| 2 | 内聘重复绑定 | 通过 | 再次绑定用户 22 → “该用户已绑定其他维修人员档案” |
| 3 | 内聘跨租户 | 通过 | 租户14管理员选租户1用户(id=16) → “该用户不属于当前租户” |
| 4 | 内聘用户禁用 | 通过 | 禁用用户(id=82)后绑定 → “该用户已被禁用，请先在用户管理中启用” |
| 5 | 内聘用户无手机号 | 通过 | 无号用户(id=83)不出现在选人列表；绑定 → “该用户未维护手机号，无法登录 H5” |
| 6 | 外聘建号成功 | 通过 | 张三(id=14) 生成 sys_user(ext_14_py66yp9v, id=81)，档案 repairer_type=2 |
| 7 | 外聘手机号重复 | 通过 | 用已占用手机号(管理员19900000001)新增外聘 → “该手机号已被本租户用户占用，请改用内聘选择” |
| 8 | 外聘账号角色 | 通过 | sys_user_role 中 id=81 仅有 repairer，无 super_admin/tenant_admin |
| 9 | H5 内聘登录 | 通过 | 19900000002+123456 登录成功，/m/order/my-list 仅返回 repairer=13 自己的工单(192/191/190/189) |
| 10 | H5 外聘登录 | 通过 | 张三登录成功(repairerId=14)，my-list 可访问 |
| 11 | H5 无档案 | 通过 | 管理员手机号(19900000001)走 H5 → “当前账号未关联维修人员档案，请联系管理员” |
| 12 | H5 休假 | 通过 | 档案 status=0 后登录 → “当前维修人员为休假状态，暂不能登录 H5，请联系管理员调整在岗状态” |
| 13 | H5 离职 | 通过 | 档案 status=2 后登录 → “当前维修人员已离职，不能登录 H5” |
| 14 | H5 账号禁用 | 通过 | sys_user.status=0 后登录 → “当前账号已被禁用，请联系管理员” |
| 15 | H5 同号多档案 | 通过 | 构造同租户两用户同手机号均绑档案 → “手机号关联多个维修人员账号，请联系管理员处理”（构造数据已清理） |
| 16 | 派单后归属 | 通过 | 工单193派给外聘李四(id=18)，李四 my-list 可见且 detail 成功；他人 detail 193 → “工单不存在或不属于当前维修人员” |
| 17 | 删除外聘 | 通过 | 删除张三(id=14)：档案逻辑删除(del_flag=2，项目软删约定)，账号(id=81) status=0，H5 登录失败 |
| 18 | 修改外聘手机号 | 通过 | 张三手机号 19900000009→19900000099，repairer.phone 与 sys_user.phone 双表同步，新号可登录 |
| 19 | 存量兼容 | 通过 | 构造 user_id=NULL 档案(id=15, phone=19900000003)，H5 经手机号解析登录成功 |
| 20 | 租户隔离 | 通过 | 租户14维修员(repairer=13)访问租户33工单(188) → “工单不存在或不属于当前维修人员” |

合计：通过 20 / 失败 0 / 未自动化 0。
说明：选人列表、弹窗提示等 UI 交互均以后端接口返回值与错误 msg 取证；未在浏览器内做视觉点击，但行为断言全部来自真实接口响应。

## 4. 回滚演练结论

按裁定在独立库 netsight_rollback_drill 演练（未在主库 netsight 上 DROP）：
1. 从主库导出升级后的 repairer 表导入演练库。
2. 执行回滚 SQL：DROP INDEX uk_repairer_user / DROP INDEX idx_repairer_phone / DROP COLUMN repairer_type / DROP COLUMN user_id —— 成功(rc=0)，列与索引均被移除。
3. 重新执行升级 SQL —— 成功(rc=0)，user_id、repairer_type 与两个索引均重建。
4. 主库 netsight 核对：仍含 2 个新列、4 条索引项（2 索引 × 2 列），未被演练影响。
结论：回滚 SQL 可在备份/独立库正常执行并可逆；主库验收数据保留完好。

## 5. 残留测试数据（测试库内，非代码产物）

- repairer：id=13(已绑用户22)、id=14(张三，已删)、id=15(存量乙)、id=18(李四)；work_order 193 派给李四。
- sys_user：id=81(张三账号，已禁用)、id=82(禁用用户)、id=83(无号用户)。
- 项15 的同号双档案临时数据已在验收后清理。
