# -*- coding: utf-8 -*-
"""检查 AM 配置与 18080 可达性"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) AM 配置全文 ==")
print(run("cat /opt/alertmanager/alertmanager.yml"))

print("\n== 2) nams-agent :18080 可达性 ==")
print(run("curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:18080/alert/push -H 'Content-Type: application/json' -d '{}'"))
print("\n18080 监听:", run("ss -tlnp | grep 18080"))

print("\n== 3) AM 进程与参数 ==")
print(run("systemctl cat alertmanager | grep -E 'ExecStart|Environment'"))

ssh.close()
