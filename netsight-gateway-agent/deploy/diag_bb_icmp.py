# -*- coding: utf-8 -*-
"""验证 blackbox 0.13.0 ICMP 是否可用（探测本机 127.0.0.1）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 探测 127.0.0.1 ==")
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=127.0.0.1' | grep -E 'probe_success|phase=\"rtt\"'"))
print("\n== 探测 192.168.1.60（本机 IP） ==")
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.60' | grep -E 'probe_success|phase=\"rtt\"'"))
print("\n== 直接 ping .1 确认可达 ==")
print(run("ping -c 2 -W 1 192.168.1.1 | tail -2"))
print("\n== blackbox 进程权限 ==")
print(run("ps aux | grep blackbox | grep -v grep | head -2"))
print(run("sysctl net.ipv4.ping_group_range"))
ssh.close()
