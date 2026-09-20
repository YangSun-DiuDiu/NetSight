# -*- coding: utf-8 -*-
"""Prometheus 切 blackbox probe + recording rules + 停自研探针"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=60):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 备份 + 重写 prometheus.yml（blackbox probe 模式） ==")
run("cp /etc/prometheus/prometheus.yml /etc/prometheus/prometheus.yml.bak.$(date +%s)")
prom = """# NetSight 边缘网关 Prometheus 配置（真实生产环境）
# 设备探测走 Prometheus 全家桶 blackbox_exporter(ICMP probe) → 告警规则 → Alertmanager → nams-agent(:18080) → 云端
# 设备清单由云端 mapping 下发（/opt/nams-gateway/conf/targets/snmp.json，file_sd，设备增删零重启）

global:
  scrape_interval: 15s
  evaluation_interval: 15s
  scrape_timeout: 10s
  external_labels:
    monitor: 'nams-edge'

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['127.0.0.1:9093']

rule_files:
  - '/etc/prometheus/rules/nams_device_rules.yml'

scrape_configs:
  - job_name: 'prometheus'
    scrape_interval: 5s
    scrape_timeout: 5s
    static_configs:
      - targets: ['localhost:9090']

  - job_name: node
    static_configs:
      - targets: ['localhost:9100']

  # NetSight 设备探测：blackbox_exporter ICMP probe（Prometheus 官方全家桶，替代自研探针）
  # probe_success → recording rule 生成 device_up{device_code=...}，nams-agent 契约不变
  - job_name: nams_device_probe
    scrape_interval: 15s
    scrape_timeout: 10s
    metrics_path: /probe
    params:
      module: [icmp]
    file_sd_configs:
      - files:
          - /opt/nams-gateway/conf/targets/snmp.json
        refresh_interval: 60s
    relabel_configs:
      - source_labels: [__address__]
        regex: ^([^:]+):\\d+$
        target_label: __param_target
        replacement: ${1}
      - source_labels: [__param_target]
        target_label: instance
      - target_label: __address__
        replacement: 127.0.0.1:9115
"""
sftp = ssh.open_sftp()
with sftp.open("/etc/prometheus/prometheus.yml", "w") as f:
    f.write(prom)
sftp.close()
print("prometheus.yml 已重写")

print("\n== 2) 加 recording rules（device_up / device_line_abnormal） ==")
run("cp /etc/prometheus/rules/nams_device_rules.yml /etc/prometheus/rules/nams_device_rules.yml.bak.$(date +%s)")
rules = run("cat /etc/prometheus/rules/nams_device_rules.yml")
print("现有规则（告警部分保持不动）:")
print(rules[:400])

# 在规则文件末尾追加 recording group（幂等：先检查是否已存在）
if "nams_device_recording" not in rules:
    rec = """

# ===== recording rules：blackbox probe → 业务指标（nams-agent 契约） =====
groups:
  - name: nams_device_recording
    rules:
      - record: device_up
        expr: probe_success{job="nams_device_probe"}
      - record: device_line_abnormal
        expr: probe_icmp_duration_seconds{job="nams_device_probe",phase="rtt"} > 0.15
"""
    sftp = ssh.open_sftp()
    with sftp.open("/etc/prometheus/rules/nams_device_rules.yml", "a") as f:
        f.write(rec)
    sftp.close()
    print("recording rules 已追加")
else:
    print("recording rules 已存在，跳过")

print("\n== 3) 重启 Prometheus（WAL 安全）+ 停自研探针 ==")
print(run("systemctl restart prometheus && sleep 8 && systemctl is-active prometheus"))
print(run("systemctl disable --now nams-collector 2>&1 | tail -1; systemctl is-active nams-collector || echo 'nams-collector 已停'"))

print("\n== 4) 验证 targets ==")
time.sleep(5)
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets?state=active' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('job'), t['health'], len(t.get('scrapeUrl',''))) for t in d['data']['activeTargets']]\""))

print("\n== 5) device_up 指标 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['metric'].get('device_code'), r['value'][1]) for r in d['data']['result']]\""))

print("\n== 6) 告警状态 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/rules' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['name'], r['state']) for g in d['data']['groups'] for r in g['rules']]\""))

ssh.close()
