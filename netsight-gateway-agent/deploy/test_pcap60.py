# -*- coding: utf-8 -*-
"""tcpdump 抓 18080 + 触发 resolved 验证 AM 是否发送 webhook"""
import paramiko
import os
import json
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=40):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 启动 tcpdump 抓 18080（背景 75s） ==")
print(run("(timeout 75 tcpdump -i any -nn -s 0 port 18080 -w /tmp/am_18080.pcap >/dev/null 2>&1 &) ; echo tcpdump-started"))

print("\n== 2) CAM-02 恢复（触发 resolved 通知） ==")
st = run("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = True
sftp = ssh.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("已置 up=True，等待 50s...")
time.sleep(50)

print("\n== 3) tcpdump 抓包统计 ==")
print(run("tcpdump -r /tmp/am_18080.pcap -nn 2>/dev/null | wc -l"))
print(run("tcpdump -r /tmp/am_18080.pcap -nn 2>/dev/null | grep -E '18080|POST' | head -10"))

print("\n== 4) Prometheus 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|state' | head -6"))

print("\n== 5) AM 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt'], a['updatedAt']) for a in d]\""))
print("\n== 6) nams-agent 最近 60s ==")
print(run("journalctl -u nams-agent --since '60 seconds ago' --no-pager | grep -iE 'alert|转投|接收' | tail -6"))

ssh.close()
