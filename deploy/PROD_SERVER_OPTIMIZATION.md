# 生产服务器环境优化基线（NetSight 生产环境）

> 用途：后期新服务器部署 / 重新装机时，按此清单一键对齐环境，避免重复踩坑。
> 基线服务器：腾讯云 KVM，Ubuntu 24.04.4 LTS，2 核 / 2 GB 内存 / 50 GB 系统盘。

---

## 一、当前生产服务器清单

| 角色 | 主机 | IP | 用途 |
|---|---|---|---|
| 应用服务器 | VM-0-10-ubuntu | 115.159.194.149 | Nginx + NetSight Java + Redis 本机 + 监控套件 |
| 数据库/缓存 | - | 1.94.26.126 | MySQL 8 + Redis（共用一台） |

应用服务器软件栈：
- Nginx（80，反代 `/prod-api/` → 127.0.0.1:8080）
- OpenJDK 21（`/usr/lib/jvm/java-21-openjdk-amd64`）
- Redis（127.0.0.1:6379，仅本机）
- NetSight jar（`/opt/nams-server/`，start.sh 启动）
- Prometheus（127.0.0.1:9090）
- Alertmanager（127.0.0.1:9093）
- snmp_exporter（127.0.0.1:9116）
- Grafana（127.0.0.1:3000）
- 腾讯云监控 agent（categraf / barad_agent / YDService，**保留**）

---

## 二、上线必做优化清单

### 1. 磁盘清理（新装机器 / 长期运行必做）

```bash
# 1) 清 journal 日志（Ubuntu 默认会无限增长，至少能省 1-3 GB）
journalctl --vacuum-size=200M

# 2) 清 /var/cache/apt
apt clean

# 3) 删掉安装包残留（如果装过）
rm -f /opt/am.tar.gz /opt/code /root/go
```

### 2. Java 启动参数（2C/2G 小内存机器必加）

`/opt/nams-server/start.sh` 启动行必须带 JVM 参数：

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  -Xms256m -Xmx512m \
  -XX:+UseG1GC \
  -jar netsight-server.jar --spring.profiles.active=prod \
  > /opt/nams-server/app.log 2>&1 &
```

> 不带 `-Xmx` 时 JVM 默认会按物理内存 1/4 申请，2GB 机器上直接吃掉 500MB+，导致 swap 频繁使用。

### 3. 监控组件必须绑 127.0.0.1（安全红线）

**Prometheus** `/etc/systemd/system/prometheus.service`：
```ini
ExecStart=/usr/local/bin/prometheus \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/data/prometheus \
  --web.listen-address=127.0.0.1:9090 \
  --web.enable-remote-write-receiver \
  --web.enable-lifecycle
```

**Alertmanager** `/etc/systemd/system/alertmanager.service`：
```ini
ExecStart=/opt/alertmanager/alertmanager \
  --config.file=/opt/alertmanager/alertmanager.yml \
  --storage.path=/opt/alertmanager/data \
  --web.listen-address=127.0.0.1:9093 \
  --log.level=info \
  --data.retention=120h
```

**snmp_exporter** `/etc/systemd/system/snmp-exporter.service`：
```ini
ExecStart=/usr/local/bin/snmp_exporter \
  --config.file=/opt/snmp_exporter/snmp.yml \
  --web.listen-address=127.0.0.1:9116
```

**Grafana** `/etc/grafana/grafana.ini` 的 `[server]` 段：
```ini
[server]
http_addr = 127.0.0.1
```

改完后：
```bash
systemctl daemon-reload
systemctl restart prometheus alertmanager snmp-exporter grafana-server
ss -tlnp | grep -E ':(3000|9090|9093|9116)'   # 应该全部是 127.0.0.1
```

> 公网要看 Grafana 走 SSH 隧道：`ssh -L 3000:127.0.0.1:3000 root@<IP>`，再访问 `http://localhost:3000`。
> 不要为了方便改回 0.0.0.0 再裸奔，腾讯云安全组只挡外部，组件本身暴露后仍可被扫描到默认弱口令。

### 4. 关闭云主机不需要的桌面/硬件服务

```bash
for svc in ModemManager multipathd udisks2 upower fwupd; do
  systemctl stop $svc 2>/dev/null
  systemctl disable $svc 2>/dev/null
done
```

理由：云主机无 Modem、无多路径盘、无电池、无固件，这些纯浪费内存。

### 5. 关闭自动升级（避免半夜系统/Java 被自动升级挂掉）

`/etc/apt/apt.conf.d/50unattended-upgrades`：
```ini
AutomaticUpdateEnable "false"
```

### 6. 业务 Java 8080 端口

当前仍监听 `0.0.0.0:8080`（nginx 反代能通，外部也能直连）。
- **可选加固**：在 `application-prod.yml` 加 `server.address: 127.0.0.1`，让外部只能通过 nginx 80 访问。
- 生产环境建议加这一项，后续部署新机器时直接做。

---

## 三、当前基线指标（优化后）

| 指标 | 数值 | 备注 |
|---|---|---|
| 磁盘使用 | 11 GB / 50 GB（24%） | 基线 31% |
| 内存已用 | ~1.0 GB / 1.9 GB | 基线 1.1 GB |
| Swap 使用 | ~166 MB | 基线 389 MB |
| 业务健康 | HTTP 200 | Nginx / Java 均正常 |
| 公网暴露端口 | 仅 22 / 80 | 其他全部收口 |

---

## 四、运维操作备忘

### 重启 NetSight（带 JVM 参数）

```bash
cd /opt/nams-server
bash start.sh
# 或手动：
# kill $(cat app.pid); bash start.sh
```

> ⚠️ `app.pid` 里的 PID 可能不准（历史出现过 stale 导致 kill 错对象）。重启前先 `ps -ef | grep netsight-server.jar` 确认，用 `pkill -f netsight-server.jar` 兜底。

### 查看实时日志

```bash
tail -f /opt/nams-server/app.log
journalctl -u prometheus -f
journalctl -u grafana-server -f
```

### 查端口占用

```bash
ss -tlnp | grep -E ':(80|8080|3000|6379|9090|9093|9116)'
```
