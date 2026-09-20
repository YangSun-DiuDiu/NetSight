# -*- coding: utf-8 -*-
"""apt 安装 blackbox_exporter"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=120):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip() + stderr.read().decode("utf-8", "ignore").strip()

print("== 1) 禁用 docker 源（AGENTS.md 坑） ==")
print(run("ls /etc/apt/sources.list.d/ 2>/dev/null"))
print(run("rm -f /etc/apt/sources.list.d/*docker* 2>/dev/null; echo cleaned"))

print("\n== 2) apt 安装 blackbox ==")
print(run("apt-get update -qq 2>&1 | tail -2; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq prometheus-blackbox-exporter 2>&1 | tail -5"))

print("\n== 3) 版本与状态 ==")
print(run("blackbox_exporter --version 2>&1 | head -2; systemctl is-active prometheus-blackbox-exporter"))
print(run("ss -tlnp | grep 9115"))

print("\n== 4) 配置路径 ==")
print(run("ls -la /etc/prometheus/blackbox.yml /etc/default/prometheus-blackbox-exporter 2>/dev/null; systemctl cat prometheus-blackbox-exporter | grep ExecStart"))

ssh.close()
