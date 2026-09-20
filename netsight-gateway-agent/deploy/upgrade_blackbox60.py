# -*- coding: utf-8 -*-
"""上传 blackbox 0.26.0 并替换 apt 旧版"""
import paramiko
import os
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=60):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 上传 0.26.0 ==")
sftp = ssh.open_sftp()
sftp.put(r"E:\gitee\NetSight1.0\netsight-gateway-agent\deploy\blackbox-0.26.0.tar.gz", "/opt/blackbox-0.26.0.tar.gz")
sftp.close()
print("上传完成")

print("\n== 2) 停 apt 旧版 + 解压新版 ==")
print(run("systemctl disable --now prometheus-blackbox-exporter >/dev/null 2>&1; cd /opt && tar xzf blackbox-0.26.0.tar.gz && rm -rf blackbox_exporter && mv blackbox_exporter-0.26.0.linux-amd64 blackbox_exporter && rm -f blackbox-0.26.0.tar.gz && ls /opt/blackbox_exporter/"))

print("\n== 3) 新版 systemd（沿用 blackbox.yml icmp module） ==")
unit = """[Unit]
Description=Prometheus Blackbox Exporter (NetSight Device Probe)
After=network.target

[Service]
Type=simple
User=root
ExecStart=/opt/blackbox_exporter/blackbox_exporter --config.file=/etc/prometheus/blackbox.yml --web.listen-address=:9115
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

print("\n== 4) 版本 + ICMP 冒烟 ==")
print(run("/opt/blackbox_exporter/blackbox_exporter --version 2>&1 | head -1"))
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.1' | grep -E '^probe_success|phase=\"rtt\"'"))
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.2' | grep -E '^probe_success|phase=\"rtt\"'"))

ssh.close()
