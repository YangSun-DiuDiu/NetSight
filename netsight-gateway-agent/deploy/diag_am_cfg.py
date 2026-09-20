# -*- coding: utf-8 -*-
"""查 AM 完整配置 + nams-agent 告警转投 + 云端事件与设备状态"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) AM 进程与配置路径 ==")
print(run("ps aux | grep alertmanager | grep -v grep"))
print(run("ls -la /etc/alertmanager/ 2>/dev/null"))
print(run("cat /etc/alertmanager/alertmanager.yml 2>/dev/null | head -40"))
print(run("systemctl cat alertmanager 2>/dev/null | grep ExecStart"))

print("\n== 2) nams-agent 告警/上报日志（宽 grep） ==")
print(run("journalctl -u nams-agent --since '10 minutes ago' --no-pager | grep -iE 'report|push|alert|snapshot|device' | tail -12"))

ssh.close()
