# -*- coding: utf-8 -*-
"""实测 8080 /alert/push 完整响应 + 云端日志"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 8080 无 token（看 body） ==")
print(run("curl -s -X POST http://127.0.0.1:8080/alert/push -H 'Content-Type: application/json' -d '{}'"))
print("\n== 2) 8080 带 token（看 body） ==")
print(run("curl -s -X POST http://127.0.0.1:8080/alert/push -H 'Content-Type: application/json' -H 'X-Netsight-Webhook-Token: 63c50a35b31943b497355daa5b988f2b' -d '{}'"))
print("\n== 3) 8080 健康检查确认 jar 版本 ==")
print(run("curl -s http://127.0.0.1:8080/actuator/health | head -c 300"))
print("\n== 4) 云端日志最近 /alert 记录 ==")
print(run("grep -E 'alert|Webhook' /opt/nams-server/logs/netsight.log | tail -15"))

ssh.close()
