# -*- coding: utf-8 -*-
"""验证 Prometheus 指标 + nams-agent prometheus 源上报链路"""
import paramiko
import os
import json

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) Prometheus 中 device_up / device_line_abnormal ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -m json.tool | grep -E 'device_code|value' | head -30"))
print("---")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_line_abnormal' | python3 -m json.tool | grep -E 'device_code|value' | head -20"))

print("\n== 2) Prometheus 当前告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|device_code|state|severity' | head -20"))

print("\n== 3) nams-agent 最近日志（上报） ==")
print(run("journalctl -u nams-agent --since '3 minutes ago' --no-pager | grep -iE 'snapshot|上报|status|device|success|fail' | tail -12"))

ssh.close()
