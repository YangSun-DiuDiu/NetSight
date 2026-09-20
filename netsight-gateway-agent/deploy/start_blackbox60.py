# -*- coding: utf-8 -*-
"""启动 blackbox_exporter + 配置 icmp module"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 当前 blackbox.yml ==")
print(run("cat /etc/prometheus/blackbox.yml"))

print("\n== 2) 写入 icmp module 配置 ==")
bb = """modules:
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
with sftp.open("/etc/prometheus/blackbox.yml", "w") as f:
    f.write(bb)
sftp.close()
print("已写入")

print("\n== 3) 启动 ==")
print(run("systemctl enable prometheus-blackbox-exporter >/dev/null 2>&1; systemctl restart prometheus-blackbox-exporter; sleep 3; systemctl is-active prometheus-blackbox-exporter"))
print(run("ss -tlnp | grep 9115"))

print("\n== 4) 冒烟：ICMP 探测 .1 ==")
print(run("curl -s --max-time 8 'http://127.0.0.1:9115/probe?module=icmp&target=192.168.1.1' | grep -E '^probe_success|^probe_icmp_duration|^probe_icmp' | head -6"))

ssh.close()
