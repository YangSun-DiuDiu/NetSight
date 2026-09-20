# -*- coding: utf-8 -*-
"""解压校验 Alertmanager 二进制"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

cmds = [
    "mkdir -p /opt/alertmanager && tar -xzf /opt/alertmanager.tar.gz -C /opt/alertmanager --strip-components=1 && ls -la /opt/alertmanager/alertmanager",
    "/opt/alertmanager/alertmanager --version 2>&1 | head -2 || echo '二进制损坏'",
]
for c in cmds:
    stdin, stdout, stderr = ssh.exec_command(c, timeout=30)
    print(stdout.read().decode().strip())

ssh.close()
