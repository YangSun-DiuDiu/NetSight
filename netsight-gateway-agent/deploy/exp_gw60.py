# -*- coding: utf-8 -*-
"""正确改密 + 查 nams-agent 内部设备状态（probe 读取结果）"""
import os
import json
import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

# 改密（正确路径 /local/api/password）
print("== 改密 ==")
cp = run('curl -s -X POST http://127.0.0.1:8081/local/api/password -H "Content-Type: application/json" -H "X-NAMS-Token: 02f3e954cf274532bc3ecf6582b77111" -d \'{"oldPwd":"admin123","newPwd":"Admin@2026"}\'')
print("password:", cp[:200])

# 重新登录
login = run('curl -s -X POST http://127.0.0.1:8081/local/api/login -H "Content-Type: application/json" -d \'{"username":"admin","password":"Admin@2026"}\'')
print("re-login:", login[:200])
try:
    token = json.loads(login)["data"]["token"]
    print("\n== 内部设备状态（probe 读取结果） ==")
    devs = run('curl -s http://127.0.0.1:8081/local/api/devices -H "X-NAMS-Token: %s"' % token)
    print(devs[:1600])
    print("\n== overview（统计） ==")
    ov = run('curl -s http://127.0.0.1:8081/local/api/overview -H "X-NAMS-Token: %s"' % token)
    print(ov[:800])
except Exception as e:
    print("解析失败:", e)

ssh.close()
