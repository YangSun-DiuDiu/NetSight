# -*- coding: utf-8 -*-
""".60 上测试华为云/腾讯云 GitHub Release 镜像"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

urls = [
    ("华为云 v0.28.1", "https://mirrors.huaweicloud.com/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
    ("华为云 LATEST", "https://mirrors.huaweicloud.com/github-release/prometheus/alertmanager/LATEST-RELEASE/"),
    ("腾讯云 v0.28.1", "https://mirrors.cloud.tencent.com/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
    ("腾讯云 LATEST", "https://mirrors.cloud.tencent.com/github-release/prometheus/alertmanager/LATEST-RELEASE/"),
    ("阿里云 v0.28.1", "https://mirrors.aliyun.com/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
]

for name, url in urls:
    cmd = "curl -s -L -o /tmp/am_test.bin -w '%%{http_code} %%{size_download}' --max-time 25 '%s'" % url
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=40)
    out = stdout.read().decode().strip()
    print("%s => %s" % (name, out))

ssh.close()
