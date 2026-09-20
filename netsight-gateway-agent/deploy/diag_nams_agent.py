# -*- coding: utf-8 -*-
"""检查 nams-agent 进程状态与完整日志"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) nams-agent 进程 ==")
print(run("ps -ef | grep nams-agent | grep -v grep; systemctl is-active nams-agent"))

print("\n== 2) 最近 30 行日志（不过滤） ==")
print(run("journalctl -u nams-agent -n 30 --no-pager | tail -30"))

print("\n== 3) 是否有崩溃循环 ==")
print(run("systemctl show nams-agent -p NRestarts -p ExecMainStartTimestamp -p ActiveState"))

ssh.close()
