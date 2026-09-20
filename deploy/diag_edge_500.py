# -*- coding: utf-8 -*-
"""edge 无 token 500：查后端日志异常栈"""
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

run("curl -s -o /dev/null -X POST http://127.0.0.1:8080/edge/report/heartbeat -H 'Content-Type: application/json' -d '{}'")
print(run("tail -n 300 /opt/nams-server/logs/stdout.log | grep -B 2 -A 8 'ERROR' | tail -60")[:2500])
client.close()
