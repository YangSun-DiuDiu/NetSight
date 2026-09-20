# -*- coding: utf-8 -*-
"""确认工单表状态与事件→工单联动"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run55(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 工单总数与最近 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT COUNT(*) AS total FROM work_order; SELECT id,order_no,device_name,status,create_time FROM work_order ORDER BY id DESC LIMIT 5;\""))

print("\n== 2) Redis 工单去重键 ==")
print(run55("redis-cli -n 0 --scan --pattern 'netsight:order:dedup:*' 2>/dev/null"))

print("\n== 3) 事件最新 5 条 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT id,event_type,device_name,status,create_time FROM event_record ORDER BY id DESC LIMIT 5;\""))

print("\n== 4) 后端日志（事件→工单处理） ==")
print(run55("journalctl -u nams-server --since '1 hour ago' --no-pager | grep -iE '工单|order|AlertEvent|autoCreate' | tail -8"))

ssh.close()
