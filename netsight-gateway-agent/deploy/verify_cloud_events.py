# -*- coding: utf-8 -*-
"""查云端事件（告警转投结果）"""
import paramiko
import os

ssh55 = paramiko.SSHClient()
ssh55.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh55.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run55(cmd, timeout=30):
    stdin, stdout, stderr = ssh55.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 最近 40 分钟事件 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT id,event_type,severity,device_name,device_ip,status,create_time FROM event_record WHERE create_time > NOW() - INTERVAL 40 MINUTE ORDER BY create_time DESC LIMIT 12;\""))

print("\n== 2) 最近通知日志 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT event_id,event_type,channel_type,success,create_time FROM notification_log WHERE create_time > NOW() - INTERVAL 40 MINUTE ORDER BY create_time DESC LIMIT 8;\""))

print("\n== 3) 最近工单 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT order_no,device_name,status,create_time FROM work_order WHERE create_time > NOW() - INTERVAL 40 MINUTE ORDER BY create_time DESC LIMIT 6;\""))

ssh.close()
