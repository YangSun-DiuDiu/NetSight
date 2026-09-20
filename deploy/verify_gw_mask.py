# -*- coding: utf-8 -*-
"""验证网关列表 gatewayToken 脱敏"""
import paramiko, json

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd, t=30):
    _, out, err = client.exec_command(cmd, timeout=t)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

run("curl -s -X POST 'http://127.0.0.1:8080/auth/sms-code?phone=13800000000' -o /dev/null")
o, _ = run("""curl -s -X POST http://127.0.0.1:8080/auth/login -H "Content-Type: application/json" -d '{"phone":"13800000000","code":"123456"}'""")
token = json.loads(o).get("data", {}).get("token", "")

o, e = run("curl -s 'http://127.0.0.1:8080/edge/gateway/list?pageNum=1&pageSize=20' -H 'Authorization: Bearer %s'" % token)
print("RAW:", o[:500])
try:
    d = json.loads(o)
    rows = d.get("data", {})
    recs = rows.get("records", rows if isinstance(rows, list) else [])
    print("list code:", d.get("code"), "| count:", len(recs))
    for r in recs:
        print("gw:", r.get("gatewayName"), "| gatewayToken:", r.get("gatewayToken"), "| pushplusToken:", r.get("pushplusToken"))
except Exception as ex:
    print("parse fail:", o[:300], ex)
client.close()
