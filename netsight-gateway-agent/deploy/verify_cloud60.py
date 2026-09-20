# -*- coding: utf-8 -*-
"""验证云端全链路 + nams-agent 转发日志"""
import subprocess
import os
import paramiko

MYSQL = r"D:\mysql\mysql-8.0.19-winx64\bin\mysql.exe"
os.environ["MYSQL_PWD"] = "Chinaunicom@1358"

def q(sql):
    r = subprocess.run([MYSQL, "-h", "192.168.1.55", "-P", "3306", "-u", "sadmin", "-D", "netsight",
                        "--default-character-set=utf8", "-e", sql], capture_output=True, text=True, encoding="utf-8")
    return r.stdout.strip()

print("== 1) 最近 3 条事件 ==")
print(q("SELECT id,event_type,severity,device_code,device_name,status,create_time FROM event_record ORDER BY id DESC LIMIT 3;"))

print("\n== 2) 最近通知日志 ==")
print(q("SELECT id,event_id,event_type,channel_type,success,biz_id FROM notification_log ORDER BY id DESC LIMIT 4;"))

print("\n== 3) 最近工单 ==")
print(q("SELECT order_no,source_type,fault_type,device_name,status,create_time FROM work_order ORDER BY id DESC LIMIT 3;"))

print("\n== 4) nams-agent 11:24-11:27 完整日志 ==")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
stdin, stdout, stderr = ssh.exec_command("journalctl -u nams-agent --since '2026-09-13 11:24:30' --until '2026-09-13 11:27:00' --no-pager | grep -v '心跳' | tail -25", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh.close()
