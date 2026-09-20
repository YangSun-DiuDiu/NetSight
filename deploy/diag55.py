# -*- coding: utf-8 -*-
"""查看 .55 云端启动失败完整日志"""
import os
import paramiko

HOST = "192.168.1.55"
USER = "root"
PWD = os.environ.get("GW55_PASSWORD", "Chinaunicom@1358")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== systemd 服务状态 ==")
print(run("systemctl status nams-server --no-pager -l | head -20"))
print("\n== 最近 60 行 stdout 日志 ==")
print(run("tail -n 60 /opt/nams-server/logs/stdout.log 2>/dev/null || journalctl -u nams-server -n 60 --no-pager"))

ssh.close()
