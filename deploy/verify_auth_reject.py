# -*- coding: utf-8 -*-
"""确认 /edge/ 与 /alert/ 无 token 时的鉴权拒绝响应体"""
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd, t=20):
    _, out, err = client.exec_command(cmd, timeout=t)
    try:
        return out.read().decode("utf-8", "ignore").strip()
    except Exception:
        return "READ_TIMEOUT"

print("edge 无token:", run("curl -s -X POST http://127.0.0.1/edge/report/heartbeat -H 'Content-Type: application/json' -d '{}'")[:200])
print("alert 无token:", run("curl -s -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'")[:200])
client.close()
