# -*- coding: utf-8 -*-
"""OCR 修复后接口级回归验证（.55）：
1. nams.env 含 PUSHPLUS_MOCK=false
2. health / sms / login / getInfo 链路
3. /edge/ 网关心跳 + 状态上报（跨租户回归：租户1 token 上报租户7 设备不应篡改）
"""
import paramiko, json, time

HOST="192.168.1.55"; USER="root"; PWD="Chinaunicom@1358"
BASE="http://127.0.0.1:8080"
GW_TOKEN="e3f2a1b4c5d6e7f8091a2b3c4d5e6f70"   # 厂区边缘网关（租户1）
T7_DEVICE="DEV-FACIAL-01"                      # 租户7 大华人脸识别 .76

cli=paramiko.SSHClient(); cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect(HOST, username=USER, password=PWD, timeout=15)

def run(cmd, t=30):
    _,out,err=cli.exec_command(cmd, timeout=t)
    return out.read().decode("utf-8","replace"), err.read().decode("utf-8","replace")

print("== 1. nams.env 检查 ==")
out,_=run("grep -E 'PUSHPLUS_MOCK|WEBHOOK_TOKEN' /opt/nams-server/nams.env")
print(out.strip() or "!! 未找到 PUSHPLUS_MOCK")

print("== 2. health ==")
o,_=run(f"curl -s --max-time 5 -o /dev/null -w '%{{http_code}}' {BASE}/actuator/health"); print("health:",o)

print("== 3. 登录链路 ==")
run(f"curl -s --max-time 5 -X POST '{BASE}/auth/sms-code?phone=13800000000' >/dev/null")
o,_=run(f"curl -s --max-time 5 -X POST {BASE}/auth/login -H 'Content-Type: application/json' -d '{{\"phone\":\"13800000000\",\"code\":\"123456\"}}'")
try:
    login=json.loads(o)
    token=login.get("data",{}).get("token","")
    print("login code:",login.get("code"),"hasToken:",bool(token))
except Exception as e:
    print("login 解析失败:", o[:300]); token=""

if token:
    o,_=run(f"curl -s --max-time 5 {BASE}/getInfo -H 'Authorization: Bearer {token}'")
    try:
        gi=json.loads(o)
        print("getInfo code:",gi.get("code"),"user:",gi.get("data",{}).get("user",{}).get("userName"),
              "roles:",gi.get("data",{}).get("roles"))
    except Exception:
        print("getInfo 解析失败:", o[:300])

print("== 4. /edge/ 网关心跳 ==")
o,_=run(f"curl -s --max-time 5 -o /dev/null -w '%{{http_code}}' -X POST {BASE}/edge/report/heartbeat -H 'X-Gateway-Token: {GW_TOKEN}' -H 'Content-Type: application/json' -d '{{\"ipAddress\":\"192.168.1.60\"}}'")
print("heartbeat:",o)

print("== 5. Critical#1 跨租户回归：租户1 token 上报租户7 设备 up=1 ==")
o,_=run(f"curl -s --max-time 5 -X POST {BASE}/edge/report/status -H 'X-Gateway-Token: {GW_TOKEN}' -H 'Content-Type: application/json' -d '{{\"ipAddress\":\"192.168.1.60\",\"devices\":[{{\"deviceCode\":\"{T7_DEVICE}\",\"up\":1,\"lineAbnormal\":0}}]}}'")
print("上报响应:", o[:200])
o,_=run(f"mysql -h127.0.0.1 -usadmin -pChinaunicom@1358 netsight --default-character-set=utf8mb4 -N -e \"SELECT device_code,status,line_status FROM device WHERE device_code='{T7_DEVICE}'\" 2>/dev/null")
print("租户7 设备现状（应保持原值 status/line_status 未被篡改）:", o.strip())

print("== 6. Spare/Knowledge 接口冒烟（登录态） ==")
if token:
    for path in ["/spare/part/stats","/knowledge/fault/options","/dashboard/overview"]:
        o,_=run(f"curl -s --max-time 5 -o /dev/null -w '%{{http_code}}' {BASE}{path} -H 'Authorization: Bearer {token}'")
        print(path,"->",o)

cli.close()
print("DONE")
