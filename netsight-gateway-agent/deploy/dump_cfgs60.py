# -*- coding: utf-8 -*-
"""拉取 .60 Prometheus/Alertmanager/Blackbox 实际配置文件"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("########## 1) prometheus.yml ##########")
print(run("cat /etc/prometheus/prometheus.yml"))

print("\n########## 2) blackbox.yml ##########")
print(run("cat /etc/prometheus/blackbox.yml"))

print("\n########## 3) alertmanager.yml ##########")
print(run("cat /opt/alertmanager/alertmanager.yml"))

print("\n########## 4) 规则文件 nams_device_rules.yml ##########")
print(run("cat /etc/prometheus/rules/nams_device_rules.yml"))

print("\n########## 5) 服务启动参数 ##########")
print(run("systemctl cat prometheus | grep -E 'ExecStart|User'"))
print(run("systemctl cat blackbox_exporter | grep -E 'ExecStart|User'"))
print(run("systemctl cat alertmanager | grep -E 'ExecStart|User'"))

print("\n########## 6) targets/snmp.json（file_sd） ##########")
print(run("cat /opt/nams-gateway/conf/targets/snmp.json | python3 -m json.tool | head -50"))

ssh.close()
