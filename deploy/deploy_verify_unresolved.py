# -*- coding: utf-8 -*-
"""20260917 未决项处理：Token 加密迁移 + 接口回归验证"""
import paramiko
import json

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(HOST, username=USER, password=PWD, timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

print("== 1. 数据库 Token 列迁移状态 ==")
sql = "SELECT id, LEFT(gateway_token,4) gt, LEFT(pushplus_token,4) gpt FROM edge_gateway;"
o, e = run("mysql -usadmin -pChinaunicom@1358 netsight -e \"%s\"" % sql.replace('"', '\\"'))
print(o.strip() or e.strip())
sql2 = "SELECT id, LEFT(webhook_token,4) wt, LEFT(pushplus_token,4) pt FROM sys_tenant;"
o2, e2 = run("mysql -usadmin -pChinaunicom@1358 netsight -e \"%s\"" % sql2.replace('"', '\\"'))
print(o2.strip() or e2.strip())

print("== 2. 基础接口 ==")
o, _ = run("curl -s -o /dev/null -w '%%{http_code}' http://127.0.0.1:8080/actuator/health")
print("health:", o)
o, _ = run("curl -s -X POST 'http://127.0.0.1:8080/auth/sms-code?phone=13800000000'")
print("sms-code:", o[:120])
o, _ = run("curl -s -X POST http://127.0.0.1:8080/auth/login -H 'Content-Type: application/json' -d '{\"phone\":\"13800000000\",\"code\":\"123456\",\"clientType\":\"pc\"}'")
print("login:", o[:200])
try:
    token = json.loads(o)["data"]["token"]
except Exception:
    token = None
    print("  !! login 未取到 token")
if token:
    o, _ = run("curl -s http://127.0.0.1:8080/getInfo -H 'Authorization: Bearer %s'" % token)
    print("getInfo:", o[:150])

print("== 3. .60 网关明文 Token 上报（getByToken 加密匹配）==")
o, _ = run("curl -s -o /dev/null -w '%%{http_code}' -X POST http://127.0.0.1:8080/edge/report/heartbeat -H 'X-Gateway-Token: e3f2a1b4c5d6e7f8091a2b3c4d5e6f70' -H 'X-Netsight-Tenant-Id: 1' -H 'Content-Type: application/json' -d '{}'")
print("heartbeat:", o)

print("== 4. 超管查看租户 Webhook Token（decrypt）==")
if token:
    o, _ = run("curl -s http://127.0.0.1:8080/system/tenant/7/webhook-token -H 'Authorization: Bearer %s'" % token)
    print("tenant7 webhook-token:", o[:200])

print("== 5. 超管查看租户列表（脱敏）==")
if token:
    o, _ = run("curl -s 'http://127.0.0.1:8080/system/tenant/list?pageNum=1&pageSize=10' -H 'Authorization: Bearer %s'" % token)
    print("tenant list:", o[:400])

print("== 6. 网关列表（PushPlus 脱敏后置值）==")
if token:
    o, _ = run("curl -s 'http://127.0.0.1:8080/edge/gateway/list?pageNum=1&pageSize=10' -H 'Authorization: Bearer %s'" % token)
    print("gateway list:", o[:400])

client.close()
print("DONE")
