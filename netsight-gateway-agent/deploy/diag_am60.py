# -*- coding: utf-8 -*-
"""查 Prometheus→Alertmanager 链路"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) Prometheus 配置的 Alertmanager ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alertmanagers' | python3 -m json.tool"))

print("\n== 2) Alertmanager 当前告警 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9093/api/v2/alerts' | python3 -m json.tool 2>/dev/null | grep -E 'name|status|state' | head -20 || echo '(查询失败)'"))

print("\n== 3) Alertmanager 日志 ==")
print(run("journalctl -u alertmanager -n 30 --no-pager | tail -30"))

print("\n== 4) Alertmanager 配置校验 ==")
print(run("/opt/alertmanager/alertmanager --version 2>&1 | head -1; amtool check-config /opt/alertmanager/alertmanager.yml 2>&1 | tail -2 || echo '(无 amtool)'"))

ssh.close()
