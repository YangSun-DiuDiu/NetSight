# -*- coding: utf-8 -*-
"""迁移后验证：DB密文、.60网关链路、接口脱敏/明文"""
import paramiko, json

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd, t=30):
    _, out, err = client.exec_command(cmd, timeout=t)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

print("===== 1. DB Token 列迁移状态（应为 enc: 前缀） =====")
q = """SELECT 'edge_gateway' t, id, LEFT(gateway_token,8) gw, LEFT(IFNULL(pushplus_token,''),8) pp FROM edge_gateway WHERE del_flag=0
UNION ALL SELECT 'sys_tenant', id, LEFT(IFNULL(webhook_token,''),8), LEFT(IFNULL(pushplus_token,''),8) FROM sys_tenant WHERE del_flag=0;"""
o, e = run("mysql -usadmin -pChinaunicom@1358 netsight --default-character-set=utf8mb4 -e \"%s\"" % q.replace('"', '\\"'))
print(o.strip() or e.strip())

print("\n===== 2. .60 网关心跳上报（X-Gateway-Token 明文 e3f2...） =====")
o, e = run("""curl -s -o /tmp/hb.json -w "%%{http_code}" -X POST http://127.0.0.1:8080/edge/report/heartbeat -H "Content-Type: application/json" -H "X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70" -H "X-Netsight-Tenant-Id: 1" -d '{"ipAddress":"192.168.1.60"}'""")
print("heartbeat HTTP:", o.strip())
o2, _ = run("cat /tmp/hb.json")
print("body:", o2.strip()[:200])

print("\n===== 3. .60 网关拉取 mapping =====")
o, e = run("""curl -s -o /tmp/map.json -w "%%{http_code}" http://127.0.0.1:8080/edge/config/mapping -H "X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70" -H "X-Netsight-Tenant-Id: 1" """)
print("mapping HTTP:", o.strip())
o2, _ = run("head -c 200 /tmp/map.json")
print("body:", o2.strip()[:200])

print("\n===== 4. 登录 admin 拿 token =====")
o, e = run("""curl -s -X POST http://127.0.0.1:8080/auth/sms-code?phone=13800000000 -o /dev/null -w "%%{http_code}" """)
print("sms-code HTTP:", o.strip())
login = """curl -s -X POST http://127.0.0.1:8080/auth/login -H "Content-Type: application/json" -d '{"phone":"13800000000","code":"123456"}'"""
o, e = run(login)
try:
    data = json.loads(o)
    token = data.get("data", {}).get("token", "")
    print("login code:", data.get("code"), "token len:", len(token))
except Exception as ex:
    print("login parse fail:", o[:200], e[:200])
    token = ""

print("\n===== 5. 网关列表脱敏（gatewayToken 应为掩码） =====")
o, e = run("curl -s http://127.0.0.1:8080/device/gateway/list -H 'Authorization: Bearer %s' | head -c 600" % token)
try:
    d = json.loads(o)
    rows = d.get("data", {}).get("records", d.get("data", []))
    for r in (rows if isinstance(rows, list) else [])[:3]:
        print("gateway:", r.get("gatewayName"), "| token:", r.get("gatewayToken"), "| pp:", r.get("pushplusToken"))
except Exception as ex:
    print("list parse fail:", o[:300])

print("\n===== 6. 租户7 webhook token 查看（应返回明文 c9e8...） =====")
o, e = run("curl -s http://127.0.0.1:8080/system/tenant/7/webhook-token -H 'Authorization: Bearer %s' | head -c 300" % token)
print(o.strip()[:300])

print("\n===== 7. 启动日志迁移结果 =====")
o, e = run("tail -n 500 /opt/nams-server/logs/stdout.log | grep -E 'Token 加密迁移|Token 迁移失败|TokenCrypto' | tail -8")
print(o.strip() or "无迁移日志")
client.close()
