# -*- coding: utf-8 -*-
"""最终验证：mapping 接口 + nams-agent 清单同步"""
import paramiko
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 健康 ==")
print(run("systemctl is-active netsight-server"))

print("\n== 2) mapping 接口（直连 8080） ==")
out = run("curl -s --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70'")
print(out[:800])

print("\n== 3) 错误 Token 应 500（Token 无效业务异常） ==")
print(run("curl -s --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: bad' | head -c 200"))

print("\n== 4) Nginx /edge/ 代理 ==")
print(run("curl -s --max-time 8 http://127.0.0.1/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' | head -c 200"))

ssh.close()

print("\n== 5) 等 70s 查 nams-agent 清单同步（应 8 台） ==")
time.sleep(70)
ssh2 = paramiko.SSHClient()
ssh2.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh2.connect("192.168.1.60", username="root", password="Chinaunicom@1358", timeout=15)
stdin, stdout, stderr = ssh2.exec_command("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -E '清单|targets|401' | tail -4", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh2.close()
