# -*- coding: utf-8 -*-
"""查告警转投链路：AM 配置 + nams-agent 日志 + 云端状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) AM receiver 配置 ==")
print(run("grep -A5 'receivers\\|webhook' /etc/alertmanager/alertmanager.yml 2>/dev/null | head -20 || cat /etc/alertmanager/alertmanager.yml 2>/dev/null | head -30"))

print("\n== 2) AM 告警通知日志 ==")
print(run("journalctl -u alertmanager --since '5 minutes ago' --no-pager | grep -iE 'notify|webhook|error|alert' | tail -8"))

print("\n== 3) nams-agent 全部最近日志 ==")
print(run("journalctl -u nams-agent -n 20 --no-pager | tail -20"))

print("\n== 4) nams-agent 配置 ==")
print(run("cat /opt/nams-gateway/conf/config.json 2>/dev/null | head -20"))

ssh.close()
