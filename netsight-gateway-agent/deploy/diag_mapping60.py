# -*- coding: utf-8 -*-
"""探查 MappingSync 401：config.json + 云端清单接口"""
import paramiko
import os
import json

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) config.json 全文 ==")
print(run("cat /opt/nams-gateway/conf/config.json"))

print("\n== 2) MappingSync 相关日志（含 401 上下文） ==")
print(run("journalctl -u nams-agent --since '15 minutes ago' --no-pager | grep -B2 -A2 '401' | tail -15"))

ssh.close()
