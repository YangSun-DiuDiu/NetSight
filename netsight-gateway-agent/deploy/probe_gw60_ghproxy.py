# -*- coding: utf-8 -*-
""".60 测试 GitHub 加速代理下载 Alertmanager"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

gh = "https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"
proxies = [
    ("ghproxy.com", "https://ghproxy.com/" + gh),
    ("gh-proxy.com", "https://gh-proxy.com/" + gh),
    ("mirror.ghproxy.com", "https://mirror.ghproxy.com/" + gh),
    ("ghfast.top", "https://ghfast.top/" + gh),
    ("gh.api.99988866.xyz", "https://gh.api.99988866.xyz/" + gh),
]

for name, url in proxies:
    cmd = "curl -s -L -o /tmp/am_proxy.bin -w '%%{http_code} %%{size_download}' --max-time 30 '%s'" % url
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=45)
    out = stdout.read().decode().strip()
    print("%s => %s" % (name, out))

ssh.close()
