# -*- coding: utf-8 -*-
"""补查工单 + Redis 去重键"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run55(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 最近工单 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT id,order_no,device_name,device_ip,fault_type,status,source_event_id,create_time FROM work_order WHERE create_time > NOW() - INTERVAL 50 MINUTE ORDER BY create_time DESC LIMIT 8;\""))

print("\n== 2) 事件去重 Redis 键 ==")
print(run55("redis-cli -n 0 --scan --pattern 'netsight:event:dedup:*' 2>/dev/null | tail -8 || echo 'redis-cli 不可用'"))

print("\n== 3) 事件总数（近 1 小时） ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT COUNT(*) AS cnt, COUNT(DISTINCT device_ip) AS ips FROM event_record WHERE create_time > NOW() - INTERVAL 1 HOUR;\""))

ssh.close()
