# -*- coding: utf-8 -*-
"""查 nams-collector 运行状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 探针日志 ==")
print(run("journalctl -u nams-collector -n 20 --no-pager | tail -20"))

print("\n== 2) 端口监听 ==")
print(run("ss -tlnp | grep 9258"))

print("\n== 3) python3 版本 ==")
print(run("python3 --version; python3 -c 'import json,re,subprocess,threading; print(\"deps-ok\")'"))

ssh.close()
