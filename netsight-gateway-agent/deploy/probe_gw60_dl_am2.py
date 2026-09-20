# -*- coding: utf-8 -*-
"""重试下载 Alertmanager（轮换代理 + 完整性校验）"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

gh = "https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"
proxies = [
    "https://gh-proxy.com/%s",
    "https://ghfast.top/%s",
    "https://ghproxy.net/%s",
]

for i, proxy in enumerate(proxies):
    url = proxy % gh
    cmd = "rm -f /opt/alertmanager.tar.gz; curl -s -L -o /opt/alertmanager.tar.gz --max-time 240 '%s'; ls -la /opt/alertmanager.tar.gz; file /opt/alertmanager.tar.gz" % url
    print("== 尝试 %s ==" % proxy)
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=260)
    print(stdout.read().decode().strip())
    # 检查大小
    stdin, stdout, stderr = ssh.exec_command("stat -c %s /opt/alertmanager.tar.gz 2>/dev/null || stat -c %s /opt/alertmanager.tar.gz", timeout=15)
    size = stdout.read().decode().strip()
    print("size:", size)
    if size.isdigit() and int(size) > 30000000:
        print("下载完整，使用此包")
        break
    time.sleep(2)

ssh.close()
