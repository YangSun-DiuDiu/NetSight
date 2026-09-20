# -*- coding: utf-8 -*-
"""重新验证探针指标 + Prometheus 抓取 + device_up"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 探针指标 ==")
print(run("curl -s --max-time 5 http://127.0.0.1:9258/metrics | head -20"))

print("\n== 2) 等 20s 查 Prometheus 抓取 ==")
time.sleep(20)
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets?state=active' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('job'), t['health']) for t in d['data']['activeTargets']]\""))

print("\n== 3) Prometheus device_up ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -m json.tool | grep -E 'device_code|\\\"value\\\"' | head -20"))

print("\n== 4) 告警规则状态 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/rules' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(r['name'], r['state']) for g in d['data']['groups'] for r in g['rules']]\""))

ssh.close()
