# -*- coding: utf-8 -*-
""".60 用 ghfast.top 完整下载 Alertmanager 并校验"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

gh = "https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"
cmd = "curl -s -L -o /opt/alertmanager.tar.gz -w 'HTTP:%%{http_code} SIZE:%%{size_download}' --max-time 180 'https://ghfast.top/%s'" % gh
stdin, stdout, stderr = ssh.exec_command(cmd, timeout=200)
print(stdout.read().decode().strip())
stdin, stdout, stderr = ssh.exec_command("ls -la /opt/alertmanager.tar.gz; file /opt/alertmanager.tar.gz; tar -tzf /opt/alertmanager.tar.gz | head -3", timeout=30)
print(stdout.read().decode().strip())

ssh.close()
