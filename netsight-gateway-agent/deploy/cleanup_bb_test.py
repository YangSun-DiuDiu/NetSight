# -*- coding: utf-8 -*-
"""确认工单 24h 去重 + 清理测试期重复事件/通知"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run55(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 全部工单（确认 24h 去重，无重复新单） ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT id,order_no,device_name,status,source_event_id,create_time FROM work_order ORDER BY id DESC LIMIT 10;\""))

print("\n== 2) 近 1 小时事件按设备去重统计 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT device_ip,COUNT(*) cnt,MAX(id) latest_id FROM event_record WHERE create_time > NOW() - INTERVAL 1 HOUR GROUP BY device_ip;\""))

print("\n== 3) 清理：保留每台设备最新 1 条事件 + 其通知日志 ==")
print(run55("""
MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight <<'SQL'
-- 找到应保留的事件 id
CREATE TEMPORARY TABLE keep_ev AS
SELECT MAX(id) AS id FROM event_record
WHERE create_time > NOW() - INTERVAL 1 HOUR
  AND event_type='device_offline'
GROUP BY device_ip;
-- 删除多余事件（保留 keep_ev 中 id）
DELETE er FROM event_record er
WHERE er.create_time > NOW() - INTERVAL 1 HOUR
  AND er.event_type='device_offline'
  AND er.id NOT IN (SELECT id FROM keep_ev);
-- 清理孤儿通知日志（保留事件对应的通知）
DELETE nl FROM notification_log nl
LEFT JOIN event_record er ON nl.event_id = er.id
WHERE er.id IS NULL
  AND nl.create_time > NOW() - INTERVAL 1 HOUR;
-- 清理操作日志（近1小时 user_operation）
DELETE FROM event_record
WHERE event_type='user_operation' AND create_time > NOW() - INTERVAL 1 HOUR;
DROP TEMPORARY TABLE keep_ev;
SELECT '清理完成' AS msg;
SQL
"""))

print("\n== 4) 清理后复查 ==")
print(run55("MYSQL_PWD='Chinaunicom@1358' mysql -usadmin -h127.0.0.1 netsight -e \"SELECT device_ip,COUNT(*) cnt FROM event_record WHERE create_time > NOW() - INTERVAL 1 HOUR GROUP BY device_ip; SELECT COUNT(*) AS logs FROM notification_log WHERE create_time > NOW() - INTERVAL 1 HOUR; SELECT COUNT(*) AS ops FROM event_record WHERE event_type='user_operation' AND create_time > NOW() - INTERVAL 1 HOUR;\""))

ssh.close()
