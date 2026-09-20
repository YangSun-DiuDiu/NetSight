# -*- coding: utf-8 -*-
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

# 最新一次启动段（找最后一次 Started NetsightApplication 之后的所有行）
o, e = run("grep -n 'Started NetsightApplication' /opt/nams-server/logs/stdout.log | tail -3")
print("启动点:", o)
# 最后一次启动后 60 行
o2, e2 = run("tail -n 3000 /opt/nams-server/logs/stdout.log | grep -n -E 'TraceId|TokenCrypto|migrat|WARN|ERROR|Started|Startup|encrypt' | head -60")
print("尾部关键日志:", o2 or "无")
client.close()
