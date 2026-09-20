# -*- coding: utf-8 -*-
"""查 mapping 接口 500 异常堆栈"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 触发一次再查日志 ==")
print(run("curl -s --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' >/dev/null; sleep 2"))
print(run("grep -A 20 'configMapping' /opt/nams-server/logs/netsight.log | tail -25"))
print("\n== stdout.log 最近异常 ==")
print(run("grep -B2 -A15 'ERROR' /opt/nams-server/logs/netsight.log | tail -30"))

ssh.close()
