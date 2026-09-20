# -*- coding: utf-8 -*-
"""查云端新 jar 启动失败根因"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== stderr.log 前 40 行（找真正启动失败原因） ==")
print(run("head -40 /opt/nams-server/logs/stderr.log"))

print("\n== 服务状态 ==")
print(run("systemctl is-active netsight-server; tail -3 /opt/nams-server/logs/stdout.log 2>/dev/null"))

ssh.close()
