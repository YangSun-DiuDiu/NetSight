# -*- coding: utf-8 -*-
"""修复规则文件：合并重复 groups 键 + 校验 + 重启 Prometheus"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=60):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 读原规则文件 ==")
content = run("cat /etc/prometheus/rules/nams_device_rules.yml")
marker = "# ===== recording rules"
idx = content.find(marker)
base = content[:idx] if idx != -1 else content
print("基础部分行数:", len(base.splitlines()))

print("\n== 2) 重写：单个 groups 列表（告警 + recording 两个 group） ==")
recording_group = """  - name: nams_device_recording
    interval: 15s
    rules:
      - record: device_up
        expr: probe_success{job="nams_device_probe"}
      - record: device_line_abnormal
        expr: probe_icmp_duration_seconds{job="nams_device_probe",phase="rtt"} > 0.15
"""
new_content = base.rstrip() + "\n" + recording_group
sftp = ssh.open_sftp()
with sftp.open("/etc/prometheus/rules/nams_device_rules.yml", "w") as f:
    f.write(new_content)
sftp.close()
print("已重写，完整内容：")
print(new_content)

print("\n== 3) promtool 校验 ==")
print(run("/usr/bin/promtool check rules /etc/prometheus/rules/nams_device_rules.yml 2>&1 | tail -3"))

print("\n== 4) 重启 Prometheus ==")
print(run("systemctl restart prometheus && sleep 10 && systemctl is-active prometheus"))

print("\n== 5) targets ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets?state=active' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('job'), t['health'], len(t.get('scrapeUrl',''))) for t in d['data']['activeTargets']]\""))

print("\n== 6) device_up ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['metric'].get('device_code'), r['value'][1]) for r in d['data']['result']]\""))

print("\n== 7) 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/rules' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['name'], r['state']) for g in d['data']['groups'] for r in g['rules']]\""))

ssh.close()
