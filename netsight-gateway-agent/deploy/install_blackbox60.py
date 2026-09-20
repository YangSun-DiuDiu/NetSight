# -*- coding: utf-8 -*-
"""在 .60 安装 blackbox_exporter（清华镜像）+ systemd 配置"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=60):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 探测清华镜像 blackbox 0.26.0 ==")
print(run("curl -sI --max-time 10 'https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/blackbox_exporter/0.26.0/blackbox_exporter-0.26.0.linux-amd64.tar.gz' | head -3"))

print("\n== 2) 下载并解压 ==")
print(run("cd /opt && curl -sL --max-time 120 -o blackbox.tar.gz 'https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/blackbox_exporter/0.26.0/blackbox_exporter-0.26.0.linux-amd64.tar.gz' && tar xzf blackbox.tar.gz && mv blackbox_exporter-0.26.0.linux-amd64 blackbox_exporter && rm -f blackbox.tar.gz && ls -la /opt/blackbox_exporter/ | head -5"))

print("\n== 3) 写 blackbox.yml ==")
bb_conf = """modules:
  icmp:
    prober: icmp
    timeout: 5s
    icmp:
      preferred_ip_protocol: ip4
  tcp_connect:
    prober: tcp
    timeout: 5s
"""
sftp = ssh.open_sftp()
with sftp.open("/opt/blackbox_exporter/blackbox.yml", "w") as f:
    f.write(bb_conf)
sftp.close()

print("\n== 4) systemd 单元 + 启动 ==")
unit = """[Unit]
Description=Prometheus Blackbox Exporter (NetSight Device Probe)
After=network.target

[Service]
Type=simple
User=root
ExecStart=/opt/blackbox_exporter/blackbox_exporter --config.file=/opt/blackbox_exporter/blackbox.yml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
"""
sftp = ssh.open_sftp()
with sftp.open("/etc/systemd/system/blackbox_exporter.service", "w") as f:
    f.write(unit)
sftp.close()
print(run("systemctl daemon-reload && systemctl enable blackbox_exporter >/dev/null 2>&1 && systemctl restart blackbox_exporter && sleep 3 && systemctl is-active blackbox_exporter"))

print("\n== 5) 验证 :9115/metrics ==")
print(run("curl -s --max-time 5 http://127.0.0.1:9115/metrics | head -8"))

ssh.close()
