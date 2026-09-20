# -*- coding: utf-8 -*-
"""查云端日志 10:58 前后 /alert/push 与事件处理"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 10:57-11:01 全部 alert/push 记录 ==")
print(run("grep -E '10:5[789]|11:00|11:01' /opt/nams-server/logs/netsight.log | grep -iE 'alert|push|webhook|事件|工单' | tail -25"))

print("\n== 2) 日志文件时间范围 ==")
print(run("head -1 /opt/nams-server/logs/netsight.log; tail -1 /opt/nams-server/logs/netsight.log"))

print("\n== 3) 是否有独立 error 日志 ==")
print(run("tail -20 /opt/nams-server/logs/netsight-error.log 2>/dev/null | tail -20"))

ssh.close()
