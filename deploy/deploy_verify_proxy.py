# -*- coding: utf-8 -*-
"""实测三个代理 HTTP 返回码（修正 % 转义）"""
import paramiko

HOST = "192.168.1.55"; USER = "root"; PWD = "Chinaunicom@1358"

def run(ssh, cmd, timeout=20):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "ignore")
    err = stderr.read().decode("utf-8", "ignore")
    return (out + err).strip()

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, 22, USER, PWD, timeout=15)
for p in ["/prod-api/actuator/health", "/edge/", "/alert/"]:
    code = run(ssh, "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1" + p)
    print(p, "->", code)
ssh.close()
print("DONE")
