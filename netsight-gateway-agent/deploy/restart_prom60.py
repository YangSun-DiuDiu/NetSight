# -*- coding: utf-8 -*-
"""重启 Prometheus 恢复推送，验证 AM 同步 + webhook 通知"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 重启 Prometheus ==")
print(run("systemctl restart prometheus; sleep 8; systemctl is-active prometheus"))
print("主进程:", run("pgrep -f '/usr/bin/prometheus|prometheus --' | head -3"))

print("\n== 2) 30s 后查 AM 状态（updatedAt 应刷新） ==")
time.sleep(30)
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin); [print(a['labels'].get('alertname'), a['status']['state'], a['startsAt'], a['updatedAt']) for a in d]\""))

print("\n== 3) nams-agent 最近日志（外线 repeat 到期应重发） ==")
print(run("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -iE 'alert|转投|接收' | tail -6"))

print("\n== 4) 缓存目录 ==")
print(run("ls -la /opt/nams-gateway/cache/ | tail -3"))

ssh.close()
