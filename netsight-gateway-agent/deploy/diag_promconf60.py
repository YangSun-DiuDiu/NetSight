# -*- coding: utf-8 -*-
"""查 .60 Prometheus 配置与规则"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) prometheus.yml ==")
print(run("cat /etc/prometheus/prometheus.yml"))

print("\n== 2) 规则文件列表 ==")
print(run("ls -la /etc/prometheus/rules/ 2>/dev/null; ls /etc/prometheus/*.yml 2>/dev/null"))

print("\n== 3) node_exporter 参数（textfile 是否开） ==")
print(run("systemctl cat prometheus-node-exporter | grep -E 'ExecStart|Environment' | head -3"))

ssh.close()
