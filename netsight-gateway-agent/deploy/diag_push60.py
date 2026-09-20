# -*- coding: utf-8 -*-
"""查 Prometheus 推送 Alertmanager 状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) Prometheus 日志（alertmanager 相关） ==")
print(run("journalctl -u prometheus -n 60 --no-pager | grep -iE 'alertmanager|error|warn' | tail -15"))

print("\n== 2) Alertmanager 告警时间戳 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -m json.tool | grep -E 'startsAt|updatedAt|endsAt|fingerprint' | head -20"))

print("\n== 3) Prometheus 当前告警时间 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|activeAt|state' | head -15"))

print("\n== 4) Alertmanager 当前时间 ==")
print(run("date '+%Y-%m-%d %H:%M:%S'"))

ssh.close()
