# -*- coding: utf-8 -*-
"""测试 .60 直连 GitHub 下载速度"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

cmd1 = "curl -s -o /dev/null -w '%{http_code} %{size_download}' --max-time 20 -r 0-1048575 'https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz'"
stdin, stdout, stderr = ssh.exec_command(cmd1, timeout=40)
print("github range 请求:", stdout.read().decode())

ssh.close()
