# -*- coding: utf-8 -*-
"""查 target down 原因 + 告警全链路"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) nams_device_probe target 详情 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('device_code'), t['health'], t.get('lastError','')[:80]) for t in d['data']['activeTargets'] if 'probe' in t['labels'].get('job','')]\""))

print("\n== 2) 等 25s 查告警状态 ==")
time.sleep(25)
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['state'], a['labels'].get('device_code')) for a in d['data']['alerts']]\""))

print("\n== 3) AM 状态与通知 ==")
print(run("systemctl is-active alertmanager 2>/dev/null || echo '无 alertmanager 服务（由 nams-agent :18080 承接）'; ss -tlnp | grep 9093"))
print(run("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -iE 'alert|转投|webhook|18080' | tail -5"))

ssh.close()
