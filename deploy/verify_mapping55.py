# -*- coding: utf-8 -*-
"""验证 /edge/config/mapping 接口 + nams-agent 401 消失"""
import paramiko
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 云端 mapping 接口（网关 Token） ==")
print(run("curl -s --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' | python3 -m json.tool | head -30"))

print("\n== 2) 错误 Token 应 401/失败 ==")
print(run("curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: bad-token'"))

ssh.close()

print("\n== 3) 等 70s 查 nams-agent 401 是否消失 ==")
time.sleep(70)
ssh2 = paramiko.SSHClient()
ssh2.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh2.connect("192.168.1.60", username="root", password="Chinaunicom@1358", timeout=15)
stdin, stdout, stderr = ssh2.exec_command("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -E '清单|401|targets' | tail -5", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh2.close()
