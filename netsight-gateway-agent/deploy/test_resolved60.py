# -*- coding: utf-8 -*-
"""决定性测试：恢复 CAM-02 看 resolved 通知是否发出"""
import paramiko
import os
import json
import time

ssh60 = paramiko.SSHClient()
ssh60.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh60.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run60(cmd, timeout=30):
    stdin, stdout, stderr = ssh60.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

# 记录当前时间基线
base = run60("date '+%H:%M:%S'")
print("当前时间:", base)

print("== 1) CAM-02 恢复在线 ==")
st = run60("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = True
sftp = ssh60.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("已置 up=True，等待 45s...")
time.sleep(45)

print("\n== 2) Prometheus 告警状态（离线应消失） ==")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|state' | head -8"))

print("\n== 3) Alertmanager 告警状态（离线应 resolved/消失） ==")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -m json.tool | grep -E 'state|startsAt|updatedAt' | head -12"))

print("\n== 4) nams-agent 最近 60s 日志 ==")
print(run60("journalctl -u nams-agent --since '60 seconds ago' --no-pager | grep -iE 'alert|转投|push|接收' | tail -8"))

print("\n== 5) 缓存目录 ==")
print(run60("ls -la /opt/nams-gateway/cache/ | tail -3"))

ssh60.close()
