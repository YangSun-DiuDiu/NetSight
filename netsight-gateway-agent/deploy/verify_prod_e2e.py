# -*- coding: utf-8 -*-
"""真实生产链路最终验证"""
import subprocess
import os
import time
import paramiko

MYSQL = r"D:\mysql\mysql-8.0.19-winx64\bin\mysql.exe"
os.environ["MYSQL_PWD"] = "Chinaunicom@1358"

def q(sql):
    r = subprocess.run([MYSQL, "-h", "192.168.1.55", "-P", "3306", "-u", "sadmin", "-D", "netsight",
                        "--default-character-set=utf8", "-e", sql], capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip()

print("== 1) 设备状态（云端，11:55 后真实探针上报） ==")
print(q("SELECT device_code,status,line_status,update_time FROM device WHERE id<=8 ORDER BY id;"))

print("\n== 2) 最近事件 10 条 ==")
print(q("SELECT id,event_type,device_name,status,create_time FROM event_record ORDER BY id DESC LIMIT 10;"))

print("\n== 3) 最近通知日志 6 条 ==")
print(q("SELECT id,event_id,event_type,channel_type,success FROM notification_log ORDER BY id DESC LIMIT 6;"))

print("\n== 4) 最近工单 6 条 ==")
print(q("SELECT id,order_no,fault_type,device_name,status,create_time FROM work_order ORDER BY id DESC LIMIT 6;"))

print("\n== 5) nams-agent 转投/上报日志 ==")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
stdin, stdout, stderr = ssh.exec_command("journalctl -u nams-agent --since '3 minutes ago' --no-pager | grep -E '转投|上报|forward|200|401' | tail -6", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh.close()
