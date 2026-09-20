# -*- coding: utf-8 -*-
"""清空 AM storage 重启 + 触发新告警 + tcpdump"""
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

print("== 1) 停 AM + 清 storage + 启动 ==")
print(run("systemctl stop alertmanager; rm -rf /opt/alertmanager/data; systemctl start alertmanager; sleep 8; systemctl is-active alertmanager"))

print("\n== 2) tcpdump（90s）+ CAM-02 恢复再离线（干净新周期） ==")
print(run("(timeout 90 tcpdump -i any -nn -s 0 port 18080 -w /tmp/am_18080c.pcap >/dev/null 2>&1 &) ; echo tcpdump-started"))
st = run("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = True
sftp = ssh.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("CAM-02 恢复，等 25s")
time.sleep(25)
st = run("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = False
sftp = ssh.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("CAM-02 离线（新周期），等 60s...")
time.sleep(60)

print("\n== 3) tcpdump 抓包 ==")
print(run("tcpdump -r /tmp/am_18080c.pcap -nn 2>/dev/null | wc -l"))
print(run("tcpdump -r /tmp/am_18080c.pcap -nn 2>/dev/null | head -8"))

print("\n== 4) AM 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt']) for a in d]\""))

print("\n== 5) nams-agent 最近 90s ==")
print(run("journalctl -u nams-agent --since '90 seconds ago' --no-pager | grep -iE 'alert|转投|接收' | tail -8"))

ssh.close()
