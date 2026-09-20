# -*- coding: utf-8 -*-
"""部署后验证：mapping 接口 + nams-agent 清单同步"""
import paramiko
import time

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) .55 服务健康 ==")
print(run("systemctl is-active netsight-server; curl -s --max-time 5 http://127.0.0.1:8080/actuator/health | head -3"))

print("\n== 2) mapping 接口（网关 Token） ==")
out = run("curl -s --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70'")
print(out[:600])

print("\n== 3) 错误 Token ==")
print(run("curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: bad'"))

print("\n== 4) Nginx /edge/ 代理 ==")
print(run("curl -s --max-time 8 http://127.0.0.1/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' | head -c 200"))

ssh.close()

print("\n== 5) 等 70s 查 nams-agent 清单同步 ==")
time.sleep(70)
ssh2 = paramiko.SSHClient()
ssh2.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh2.connect("192.168.1.60", username="root", password="Chinaunicom@1358", timeout=15)
stdin, stdout, stderr = ssh2.exec_command("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -E '清单|401|targets' | tail -5", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
stdin, stdout, stderr = ssh2.exec_command("ls -la /opt/nams-gateway/cache/ | tail -3", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh2.close()
