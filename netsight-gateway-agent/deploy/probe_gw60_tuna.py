# -*- coding: utf-8 -*-
"""在 .60 上用清华镜像下载 Alertmanager（失败则回退本地上传）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

urls = [
    ("清华 v0.28.1", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
    ("清华 v0.27.0", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/v0.27.0/alertmanager-0.27.0.linux-amd64.tar.gz"),
    ("清华 v0.26.0", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/v0.26.0/alertmanager-0.26.0.linux-amd64.tar.gz"),
    ("清华 v0.25.0", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/v0.25.0/alertmanager-0.25.0.linux-amd64.tar.gz"),
]

for name, url in urls:
    cmd = "curl -s -L -o /tmp/am_test.bin -w '%%{http_code} %%{size_download}' --max-time 25 '%s'" % url
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=40)
    out = stdout.read().decode().strip()
    print("%s => %s" % (name, out))

ssh.close()
