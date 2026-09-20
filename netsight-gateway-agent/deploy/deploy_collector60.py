# -*- coding: utf-8 -*-
"""部署 nams-collector 自研采集探针到 .60 + 验证"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 上传采集器 ==")
ssh.exec_command("mkdir -p /opt/nams-gateway/collector")
time.sleep(1)
sftp = ssh.open_sftp()
sftp.put(r"E:\gitee\NetSight1.0\netsight-gateway-agent\deploy\nams-collector.py", "/opt/nams-gateway/collector/nams-collector.py")
sftp.close()
run("chmod +x /opt/nams-gateway/collector/nams-collector.py")
print("上传完成")

print("\n== 2) 写 systemd 单元 ==")
unit = """[Unit]
Description=NetSight Self-developed Device Collector (nams-collector)
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /opt/nams-gateway/collector/nams-collector.py
WorkingDirectory=/opt/nams-gateway
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
"""
sftp = ssh.open_sftp()
with sftp.open("/etc/systemd/system/nams-collector.service", "w") as f:
    f.write(unit)
sftp.close()
print(run("systemctl daemon-reload && systemctl enable nams-collector >/dev/null 2>&1 && systemctl restart nams-collector && sleep 3 && systemctl is-active nams-collector"))

print("\n== 3) 探针指标 ==")
print(run("curl -s --max-time 5 http://127.0.0.1:9258/metrics | head -30"))

print("\n== 4) 等 20s 查 Prometheus 抓取 ==")
time.sleep(20)
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets?state=active' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(t['labels'].get('job'), t['health'], t['scrapeUrl']) for t in d['data']['activeTargets']]\""))

print("\n== 5) Prometheus 中 device_up 查询 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -m json.tool | grep -E 'device_code|value' | head -20"))

ssh.close()
