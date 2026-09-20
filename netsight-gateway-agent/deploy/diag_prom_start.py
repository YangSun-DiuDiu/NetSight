# -*- coding: utf-8 -*-
"""查 Prometheus 启动状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 服务状态 ==")
print(run("systemctl status prometheus --no-pager -l | head -15"))
print("\n== 2) 最近日志 ==")
print(run("journalctl -u prometheus -n 15 --no-pager | tail -15"))
print("\n== 3) 配置校验 ==")
print(run("/usr/bin/prometheus --config.file=/etc/prometheus/prometheus.yml check config 2>&1 | tail -5"))
ssh.close()
