# -*- coding: utf-8 -*-
"""确认 Prometheus 推送是否停滞：间隔 30s 两次查 AM updatedAt"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 第 1 次查 AM updatedAt ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt'], a['updatedAt']) for a in d]\""))

print("\n== Prometheus 进程状态 ==")
print(run("ps -o pid,etime,%cpu,stat,cmd -p $(pgrep -f 'prometheus' | head -1)"))

print("\n== Prometheus 最近日志（全量 20 行） ==")
print(run("journalctl -u prometheus -n 20 --no-pager | tail -20"))

print("\n== 等待 35s 后第 2 次查 ==")
time.sleep(35)
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt'], a['updatedAt']) for a in d]\""))
print("\n== AM 日志最近 15 行 ==")
print(run("journalctl -u alertmanager -n 15 --no-pager | tail -15"))

ssh.close()
