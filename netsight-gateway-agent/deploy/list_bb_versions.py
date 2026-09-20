# -*- coding: utf-8 -*-
"""列出清华镜像 blackbox_exporter 可用版本"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print(run("curl -s --max-time 15 'https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/blackbox_exporter/' | grep -oE 'href=\"[0-9.]+/\"' | tr -d 'href=\"/' | sort -V | tail -8"))
ssh.close()
