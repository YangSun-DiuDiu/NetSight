"""P0-3 告警全链路测试：webhook推送→事件→规则→模板→通道→日志"""
import requests, time, json

BASE = "http://192.168.1.55/prod-api"
WEBHOOK_TOKEN = "c9e8c10ed0a4480ebf0354c112bc435e"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

def login(phone):
    requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": phone, "code": "123456", "clientType": "pc"}, timeout=10)
    return r.json().get("data", {}).get("token", "")

# 1. 模拟Alertmanager推送设备离线告警
alert_payload = {
    "receiver": "nams-cloud",
    "status": "firing",
    "alerts": [{
        "status": "firing",
        "labels": {
            "alertname": "DeviceOffline",
            "device_code": "DEV-TEST-ALERT-001",
            "device_name": "P0-3测试设备",
            "ip": "192.168.1.99",
            "tenant_id": "7",
            "severity": "critical"
        },
        "annotations": {
            "summary": "P0-3测试设备离线",
            "description": "设备192.168.1.99已离线"
        },
        "startsAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }]
}

r = requests.post(f"{BASE}/alert/push", 
    json=alert_payload,
    headers={"X-Netsight-Webhook-Token": WEBHOOK_TOKEN, "Content-Type": "application/json"},
    timeout=15)
push_resp = r.json()
test("Webhook推送（带正确token）", push_resp.get("code") == 200, f"code={push_resp.get('code')}, msg={push_resp.get('msg')}")

# 2. 无token推送应被拒绝
r2 = requests.post(f"{BASE}/alert/push", json=alert_payload, timeout=10)
test("Webhook无token拒绝", r2.json().get("code") in [401, 500], f"code={r2.json().get('code')}")

# 3. 错误token拒绝
r3 = requests.post(f"{BASE}/alert/push", json=alert_payload,
    headers={"X-Netsight-Webhook-Token": "wrong_token_12345"}, timeout=10)
test("Webhook错误token拒绝", r3.json().get("code") in [401, 500], f"code={r3.json().get('code')}")

time.sleep(3)

# 4. 验证事件已入库
admin_token = login("18667800006")
ah = {"Authorization": f"Bearer {admin_token}"}

r = requests.get(f"{BASE}/alert/event/list?pageNum=1&pageSize=10", headers=ah, timeout=10)
events = r.json().get("data", {}).get("rows", [])
test("事件列表可查", len(events) >= 1, f"最近事件数={len(events)}")

test_event = next((e for e in events if "P0-3测试设备" in str(e.get("content",""))), None)
test("测试事件已入库", test_event is not None, 
     f"事件id={test_event.get('id') if test_event else '未找到'}, type={test_event.get('eventType') if test_event else ''}")

# 5. 验证通知日志
r = requests.get(f"{BASE}/alert/log/list?pageNum=1&pageSize=10", headers=ah, timeout=10)
logs = r.json().get("data", {}).get("rows", [])
test("通知日志可查", len(logs) >= 1, f"日志数={len(logs)}")
if logs:
    latest = logs[0]
    test("通知日志字段完整", all(k in latest for k in ["channelType","success","content","eventId"]),
         f"channel={latest.get('channelType')}, success={latest.get('success')}, eventId={latest.get('eventId')}")

# 6. 规则列表
r = requests.get(f"{BASE}/alert/rule/list?pageNum=1&pageSize=20", headers=ah, timeout=10)
rules = r.json().get("data", {}).get("rows", [])
test("通知规则列表", len(rules) >= 1, f"规则数={len(rules)}")

# 7. 模板列表
r = requests.get(f"{BASE}/alert/template/list?pageNum=1&pageSize=20", headers=ah, timeout=10)
templates = r.json().get("data", {}).get("rows", [])
test("消息模板列表", len(templates) >= 1, f"模板数={len(templates)}")

# 8. 通道实例
r = requests.get(f"{BASE}/alert/channel/list?pageNum=1&pageSize=20", headers=ah, timeout=10)
channels = r.json().get("data", {}).get("rows", [])
test("通知通道实例", len(channels) >= 2, f"通道数={len(channels)}")
if channels:
    ch_types = set(c.get("channelType") for c in channels)
    test("通道类型包含pushplus+aliyun_sms", "pushplus" in ch_types and "aliyun_sms" in ch_types, f"类型={ch_types}")

# 9. 手动发送（用pushplus通道id=1）
manual_payload = {
    "eventType": "manual_test",
    "bizId": "P03-MANUAL-001",
    "content": "P0-3手动发送测试",
    "contactIds": [],
    "channels": [1]
}
r = requests.post(f"{BASE}/alert/event/manual", json=manual_payload, headers=ah, timeout=15)
test("手动发送事件", r.json().get("code") == 200, f"code={r.json().get('code')}, msg={r.json().get('msg')}")

# 登出
requests.post(f"{BASE}/auth/logout", headers=ah, timeout=10)

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P0-3 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
