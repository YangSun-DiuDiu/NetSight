# -*- coding: utf-8 -*-
"""查 AM 完整配置（/opt）+ 云端事件与设备状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) AM 配置（/opt） ==")
print(run("cat /opt/alertmanager/alertmanager.yml"))

print("\n== 2) AM 通知日志 ==")
print(run("journalctl -u alertmanager --since '10 minutes ago' --no-pager | grep -iE 'notify|error|webhook' | tail -8"))

ssh.close()

print("\n\n========== 云端 .55 检查 ==========")
ssh55 = paramiko.SSHClient()
ssh55.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh55.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run55(cmd, timeout=30):
    stdin, stdout, stderr = ssh55.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 3) 最近事件（近 30 分钟） ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT event_type,severity,device_code,status,create_time FROM event_record WHERE create_time > NOW() - INTERVAL 30 MINUTE ORDER BY create_time DESC LIMIT 10;\" 2>&1"))

print("\n== 4) 设备状态（update_time 是否刷新） ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT device_code,status,line_status,update_time FROM device ORDER BY device_code;\" 2>&1"))

print("\n== 5) 网关心跳 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT gateway_code,online_status,last_heartbeat_time FROM edge_gateway;\" 2>&1"))

ssh55.close()
