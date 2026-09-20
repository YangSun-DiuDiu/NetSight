# -*- coding: utf-8 -*-
"""修复：relabel 覆盖 file_sd 的 __metrics_path__/__param_module"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=60):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

# 在 nams_device_probe job 的 relabel_configs 中插入两个覆盖（在 __address__ 替换之前）
prom = run("cat /etc/prometheus/prometheus.yml")
anchor = """    relabel_configs:
      - source_labels: [__address__]
        regex: ^([^:]+):\\d+$
        target_label: __param_target
        replacement: ${1}
"""
fix = """    relabel_configs:
      - target_label: __metrics_path__
        replacement: /probe
      - target_label: __param_module
        replacement: icmp
      - source_labels: [__address__]
        regex: ^([^:]+):\\d+$
        target_label: __param_target
        replacement: ${1}
"""
if anchor in prom:
    prom = prom.replace(anchor, fix)
    sftp = ssh.open_sftp()
    with sftp.open("/etc/prometheus/prometheus.yml", "w") as f:
        f.write(prom)
    sftp.close()
    print("已修复 prometheus.yml（强制 __metrics_path__=/probe、__param_module=icmp）")
else:
    print("锚点未命中，打印当前 relabel 段：")
    i = prom.find("relabel_configs")
    print(prom[i:i+600])

print("\n== 校验 + 重启 ==")
print(run("/usr/bin/promtool check config /etc/prometheus/prometheus.yml 2>&1 | tail -2"))
print(run("systemctl restart prometheus && sleep 12 && systemctl is-active prometheus"))

print("\n== targets ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('device_code'), t['health'], t.get('lastError','')[:50]) for t in d['data']['activeTargets'] if 'probe' in t['labels'].get('job','')]\""))

print("\n== device_up ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['metric'].get('device_code'), r['value'][1]) for r in d['data']['result']]\""))

print("\n== 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/rules' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['name'], r['state']) for g in d['data']['groups'] for r in g['rules']]\""))

ssh.close()
