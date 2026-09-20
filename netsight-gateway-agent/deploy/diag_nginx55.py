# -*- coding: utf-8 -*-
"""确认 .55 Nginx conf 实际内容 + 直接测 8080"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== nams.conf 全文 ==")
print(run("cat /etc/nginx/conf.d/nams.conf"))

print("\n== 8080 直连 /alert/push ==")
print(run("curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8080/alert/push -H 'Content-Type: application/json' -d '{}'"))

print("\n== 80 /alert/push ==")
print(run("curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'"))

ssh.close()
