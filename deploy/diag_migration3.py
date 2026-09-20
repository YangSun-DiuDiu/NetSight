# -*- coding: utf-8 -*-
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

# stdout.log 启动段（16:22:50 之后前 300 行）
o, e = run("head -c 20000 /opt/nams-server/logs/stdout.log")
print(o)
print("=== grep Token/迁移/Crypto ===")
o2, e2 = run("grep -n -E 'TokenCrypto|迁移|encrypt|Token' /opt/nams-server/logs/stdout.log | head -40")
print(o2 or "无匹配")
client.close()
