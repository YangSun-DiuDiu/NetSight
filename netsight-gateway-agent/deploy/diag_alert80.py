# -*- coding: utf-8 -*-
"""确认 80 端口 /alert/push 请求在后端日志的痕迹"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 云端日志最近 20 行（含 IP） ==")
print(run("tail -60 /opt/nams-server/logs/netsight.log | grep -E 'alert|Webhook|192.168.1.60|status' | tail -20"))

print("\n== 2) 从 .55 本机经 80 带 token 完整测试 ==")
print(run("curl -s -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -H 'X-Netsight-Webhook-Token: 63c50a35b31943b497355daa5b988f2b' -d '{}'"))

print("\n== 3) 看这条请求是否落日志 ==")
print(run("sleep 1; tail -12 /opt/nams-server/logs/netsight.log | grep -E 'alert|Webhook' | tail -6"))

ssh.close()
