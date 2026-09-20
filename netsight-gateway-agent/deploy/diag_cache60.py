# -*- coding: utf-8 -*-
"""查 .60 nams-agent 缓存重试状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 缓存目录 ==")
print(run("ls -la /opt/nams-gateway/cache/ 2>/dev/null"))

print("\n== 2) nams-agent 最近 30 行 ==")
print(run("journalctl -u nams-agent -n 30 --no-pager | tail -30"))

ssh.close()
