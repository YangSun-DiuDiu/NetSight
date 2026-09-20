# -*- coding: utf-8 -*-
"""重启 AM + 触发新告警 + tcpdump 验证"""
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

print("== 0) nams-agent 10:53-10:56 完整日志（确认 10:54 告警来源） ==")
print(run("journalctl -u nams-agent --since '2026-09-13 10:53:00' --until '2026-09-13 10:56:00' --no-pager | grep -vE 'DEBUG|心跳' | tail -15"))

print("\n== 1) 重启 Alertmanager ==")
print(run("systemctl restart alertmanager; sleep 5; systemctl is-active alertmanager"))
print(run("journalctl -u alertmanager -n 5 --no-pager | tail -5"))

print("\n== 2) 启动 tcpdump（75s） + CAM-02 置离线 ==")
print(run("(timeout 75 tcpdump -i any -nn -s 0 port 18080 -w /tmp/am_18080b.pcap >/dev/null 2>&1 &) ; echo tcpdump-started"))
st = run("cat /opt/nams-gateway/data/device_status.json")
data = json.loads(st)
for d in data["devices"]:
    if d["deviceCode"] == "DEV-CAM-02":
        d["up"] = False
sftp = ssh.open_sftp()
with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
    f.write(json.dumps(data, ensure_ascii=False, indent=2))
sftp.close()
print("CAM-02 已置离线，等待 55s...")
time.sleep(55)

print("\n== 3) tcpdump 抓包结果 ==")
print(run("tcpdump -r /tmp/am_18080b.pcap -nn 2>/dev/null | wc -l"))
print(run("tcpdump -r /tmp/am_18080b.pcap -nn 2>/dev/null | head -6"))

print("\n== 4) AM 告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt']) for a in d]\""))

print("\n== 5) nams-agent 最近 60s ==")
print(run("journalctl -u nams-agent --since '60 seconds ago' --no-pager | grep -iE 'alert|转投|接收' | tail -8"))

ssh.close()
