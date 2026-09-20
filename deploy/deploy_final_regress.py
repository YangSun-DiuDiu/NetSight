# -*- coding: utf-8 -*-
"""最终回归：edge 无 token 401 / 网关链路 / 告警接入 / 关键接口冒烟"""
import paramiko, json

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd, t=20):
    _, out, err = client.exec_command(cmd, timeout=t)
    try:
        return out.read().decode("utf-8", "ignore").strip()
    except Exception:
        return "READ_TIMEOUT"

print("1. edge 无token（期望业务码401）:", run("curl -s -X POST http://127.0.0.1/edge/report/heartbeat -H 'Content-Type: application/json' -d '{}'")[:120])
print("2. alert 无token（期望401）:", run("curl -s -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'")[:120])
print("3. 网关心跳（带token）:", run("""curl -s -X POST http://127.0.0.1:8080/edge/report/heartbeat -H 'Content-Type: application/json' -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' -H 'X-Netsight-Tenant-Id: 1' -d '{"ipAddress":"192.168.1.60"}'""")[:120])
print("4. 网关mapping（带token）:", run("""curl -s http://127.0.0.1:8080/edge/config/mapping -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' -H 'X-Netsight-Tenant-Id: 1'""")[:120])
print("5. 租户7 webhook 鉴权反查（X-Netsight-Webhook-Token）:", run("""curl -s -X POST http://127.0.0.1:8080/alert/push -H 'Content-Type: application/json' -H 'X-Netsight-Webhook-Token: c9e8c10ed0a4480ebf0354c112bc435e' -d '{"alerts":[]}'""")[:160])
print("6. health:", run("curl -s http://127.0.0.1:8080/actuator/health")[:120])
client.close()
