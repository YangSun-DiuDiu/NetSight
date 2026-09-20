# -*- coding: utf-8 -*-
"""查云端事件/工单（nams-agent 缓存告警重试后）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 最近事件 ==")
print(run("curl -s 'http://127.0.0.1:8080/alert/event/list?pageNum=1&pageSize=10' -H 'Authorization: Bearer x' | head -c 200 || echo '(需鉴权，改查库)'"))

print("\n== 2) 云端日志最近 alert 记录 ==")
print(run("tail -30 /opt/nams-server/logs/netsight.log | grep -E 'alert|Webhook|事件|工单' | tail -12"))

ssh.close()
