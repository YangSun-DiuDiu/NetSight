# NetSight 边缘网关上报服务（nams-agent・Java 版）

独立 Java 工程，与云端后端（netsight-server）同栈：**Spring Boot 4.0.8 + JDK 21**。

职责对应《边缘网关应用部署与配置方案.md》4.7（通信中枢）与 4.8（本地管理页面）。

## 模块划分



| 包           | 职责                                                                                                    |
| ----------- | ----------------------------------------------------------------------------------------------------- |
| `connector` | 上行 HTTP：心跳 /edge/report/heartbeat、快照 /edge/report/status、告警 /alert/push、清单拉取 /edge/config/mapping（v1.1.7 已实现） |
| `wsclient`  | 下行 WebSocket /ws/gateway（**骨架预留**，当前网关走 HTTP 上报）                                                      |
| `sync`      | 采集清单同步：mapping.json → Prometheus file\_sd targets（设备增删零重启）                                            |
| `snapshot`  | 状态快照采集：查 Prometheus device\_up /device\_line\_abnormal → 批量上报                                         |
| `alerter`   | 本地 :18080 接收 AlertManager Webhook → 转投云端，失败落盘                                                         |
| `queue`     | 断网缓存补传：cache/\*.json FIFO，retry\_max=5，超限丢弃                                                           |
| `link`      | 多链路探测（骨架）：按优先级维护当前生效链路                                                                                |
| `localapi`  | 本地管理接口（/local/api/\*）：登录 / 总览 / 设备 / 同步 / 日志 / 重启 / 改密                                                |
| `web`       | 路由器风格管理页面静态资源（login.html/index.html）                                                                  |

## 构建



```
cd E:\gitee\NetSight1.0\netsight-gateway-agent

mvn clean package          # 产物 target/nams-agent.jar
```

## 本地运行（开发）



```
\# 1) 准备配置：复制 conf/mapping.json.example -> conf/mapping.json，

\#    修改 conf/config.json 的 cloud.base\_url / gateway\_token / alert\_webhook\_token

\# 2) 启动（本地 log 落项目 logs/）

\$env:JAVA\_TOOL\_OPTIONS="-Dnams.home=\$PWD -Dnams.conf=\$PWD\conf\config.json -DLOG\_PATH=\$PWD\logs"

mvn spring-boot:run

\# 访问 http://localhost:8081/login.html（默认 admin / admin123，首登强制改密）

\# 告警接收端口 18080
```

## 云端接口契约（已落地）



| 动作   | 方法 / 路径                             | 请求头                                                   |
| ---- | ----------------------------------- | ----------------------------------------------------- |
| 心跳   | `POST {base}/edge/report/heartbeat` | X-Gateway-Token + X-Netsight-Tenant-Id                |
| 状态快照 | `POST {base}/edge/report/status`    | 同上                                                    |
| 告警转投 | `POST {base}/alert/push`            | X-Netsight-Webhook-Token（租户级）                         |
| 清单拉取 | `GET {base}/edge/config/mapping`    | X-Gateway-Token（**云端 v1.1.7 已实现**：EdgeConfigController 下发，nams-agent 60s 轮询生成 file_sd targets，设备增删零重启） |

## 部署（systemd）



```
\# 网关服务器（如 192.168.1.60）

mkdir -p /opt/nams-gateway/{bin,conf,data,cache,logs,webapp}

cp target/nams-agent.jar /opt/nams-gateway/bin/

cp conf/config.json conf/mapping.json /opt/nams-gateway/conf/

cp deploy/nams-agent.service /etc/systemd/system/

systemctl daemon-reload && systemctl enable --now nams-agent

\# 验证：curl http://127.0.0.1:8081/login.html ；tail -f /opt/nams-gateway/logs/netsight.log
```

## 安全说明（对应部署方案 9.3）



* 管理页面 :8081 与告警接收 :18080 建议在防火墙层仅允许内网网段访问；

* 本地管理员密码 BCrypt 存储，config.json `password_hash` 为空时使用出厂默认 `admin123`（**生产必须预置哈希**），首登强制改密；

* 配置权威在云端：mapping.json/Token 以下发为准，本地页仅查看与应急操作。

## 已知待办

* 下行 WebSocket 指令通道（/ws/gateway）落地；

* 多链路自动切换策略增强（断 30s 内切备份 + 切换事件上报）；

* 告警接收：校验 AlertManager 签名 / 来源 IP 白名单（当前信任内网）。

## 设备探测（blackbox_exporter，2026-09-13 生产采用）

设备在线 / 链路状态探测由 **Prometheus 官方 blackbox_exporter**（ICMP probe）承担，替代自研探针（nams-collector，已停用作国产化备选，见《边缘网关应用部署与配置方案.md》第十一章）：

* Prometheus `nams_device_probe` job：`metrics_path=/probe` + `params.module=[icmp]` + file_sd（targets/snmp.json，云端 mapping 60s 刷新）+ relabel（去 SNMP 端口、强制 `__metrics_path__=/probe`、`__param_module=icmp`）；
* Recording rules：`device_up = probe_success{job="nams_device_probe"}`、`device_line_abnormal = probe_icmp_duration_seconds{...,phase="rtt"} > 0.15`——**nams-agent 查询契约（`device_up{device_code=...}`）零改动**；
* 告警规则（离线 / 外线异常）不变 → AlertManager → nams-agent :18080 → 云端 /alert/push。