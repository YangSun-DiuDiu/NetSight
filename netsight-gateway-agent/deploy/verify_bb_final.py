# -*- coding: utf-8 -*-
"""等待后最终验证 blackbox 切换"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("等待 40s 让所有 probe 完成...")
time.sleep(40)

print("== 1) targets ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('device_code'), t['health'], t.get('lastError','')[:60]) for t in d['data']['activeTargets'] if 'probe' in t['labels'].get('job','')]\""))

print("\n== 2) device_up ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['metric'].get('device_code'), r['value'][1]) for r in d['data']['result']]\""))

print("\n== 3) 告警状态 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['state'], a['labels'].get('device_code'), a['labels'].get('severity')) for a in d['data']['alerts']]\""))

print("\n== 4) nams-agent 最近日志（是否转投告警） ==")
print(run("journalctl -u nams-agent --since '3 minutes ago' --no-pager | grep -iE 'alert|webhook|18080|post' | tail -8"))

ssh.close()
