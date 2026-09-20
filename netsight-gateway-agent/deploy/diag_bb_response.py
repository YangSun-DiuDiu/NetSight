# -*- coding: utf-8 -*-
"""查 blackbox /probe 实际响应（Content-Type + body）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 响应头 ==")
print(run("curl -sI --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.1' | head -8"))

print("\n== 2) 响应 body 前 20 行 ==")
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.1' | head -20"))

print("\n== 3) 状态码 ==")
print(run("curl -s -o /dev/null -w '%{http_code}' --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.1'"))

print("\n== 4) blackbox 日志 ==")
print(run("journalctl -u blackbox_exporter -n 10 --no-pager | tail -10"))

print("\n== 5) snmp.json 前 3 条 ==")
print(run("head -c 600 /opt/nams-gateway/conf/targets/snmp.json"))

ssh.close()
