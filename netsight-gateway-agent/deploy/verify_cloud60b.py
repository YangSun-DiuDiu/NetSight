# -*- coding: utf-8 -*-
"""修正事件查询（含 stderr）"""
import subprocess
import os

MYSQL = r"D:\mysql\mysql-8.0.19-winx64\bin\mysql.exe"
os.environ["MYSQL_PWD"] = "Chinaunicom@1358"

def q(sql, show_err=True):
    r = subprocess.run([MYSQL, "-h", "192.168.1.55", "-P", "3306", "-u", "sadmin", "-D", "netsight",
                        "--default-character-set=utf8", "-e", sql], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0 and show_err:
        print("[ERR]", r.stderr.strip()[:300])
    return r.stdout.strip()

print("== 1) 事件计数 ==")
print(q("SELECT COUNT(*) AS total FROM event_record;"))

print("== 2) 最近 5 条事件 ==")
print(q("SELECT id,event_type,severity,device_code,status,create_time FROM event_record ORDER BY id DESC LIMIT 5;"))

print("\n== 3) id>=70 的事件内容 ==")
print(q("SELECT id,event_type,LEFT(content,100) AS content FROM event_record WHERE id>=70 ORDER BY id;"))

print("\n== 4) 最近通知日志 8 条 ==")
print(q("SELECT id,event_id,event_type,channel_type,success,LEFT(content,60) AS content FROM notification_log ORDER BY id DESC LIMIT 8;"))

print("\n== 5) 最近工单 5 条 ==")
print(q("SELECT id,order_no,source_type,fault_type,device_name,status,create_time FROM work_order ORDER BY id DESC LIMIT 5;"))
