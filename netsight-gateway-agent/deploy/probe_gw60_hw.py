# -*- coding: utf-8 -*-
""".60 下载华为云 Alertmanager 并校验文件类型"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

cmd = "curl -s -L -o /tmp/am_full.tar.gz -w 'HTTP:%{http_code} SIZE:%{size_download}' --max-time 120 'https://mirrors.huaweicloud.com/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz'"
stdin, stdout, stderr = ssh.exec_command(cmd, timeout=150)
print(stdout.read().decode().strip())
stdin, stdout, stderr = ssh.exec_command("ls -la /tmp/am_full.tar.gz; file /tmp/am_full.tar.gz 2>/dev/null || head -c 4 /tmp/am_full.tar.gz | xxd", timeout=20)
print(stdout.read().decode().strip())

ssh.close()
