# -*- coding: utf-8 -*-
"""对比 8080 直连 vs 80 反代"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 8080 直连 ==")
print(run("curl -s --max-time 8 -X POST http://127.0.0.1:8080/alert/push -H 'Content-Type: application/json' -d '{}' | head -c 400"))
print("\n== 2) 80 反代 ==")
print(run("curl -s --max-time 8 -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}' | head -c 400"))
print("\n== 3) Nginx 错误日志 ==")
print(run("tail -5 /var/log/nginx/error.log 2>/dev/null"))
ssh.close()
