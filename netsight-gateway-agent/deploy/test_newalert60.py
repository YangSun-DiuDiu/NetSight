# -*- coding: utf-8 -*-
"""CAM-02 离线触发新告警周期，验证 AM→nams-agent→云端全链路"""
import paramiko
import os
import json
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) CAM-02 置离线（新告警周期） ==")
st = run("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = False
sftp = ssh.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("已置 up=False，等待 40s（15s 采集 + 10s for + 通知）...")
time.sleep(40)

print("\n== 2) Prometheus 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|state|activeAt' | head -10"))

print("\n== 3) AM 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt'], a['updatedAt']) for a in d]\""))

print("\n== 4) nams-agent 最近 60s 日志（应见告警接收/转投） ==")
print(run("journalctl -u nams-agent --since '60 seconds ago' --no-pager | grep -iE 'alert|转投|接收|push|补传' | tail -10"))

print("\n== 5) 缓存目录 ==")
print(run("ls -la /opt/nams-gateway/cache/ | tail -3"))

ssh.close()
