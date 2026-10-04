"""
测试通过通道实例(notify_channel)发送数据
使用阿里云短信实例 id=4 (设备离线通知-阿里云)
"""
import requests, json, time

BASE = "http://192.168.1.55/prod-api"

# 1. 先获取验证码
print("=== 1. 获取短信验证码 ===")
r = requests.post(f"{BASE}/auth/sms-code?phone=18667800006")
print(f"验证码响应: {r.json()}")
time.sleep(1)

# 2. 登录
print("\n=== 2. 登录获取Token ===")
r = requests.post(f"{BASE}/auth/login", json={
    "phone": "18667800006",
    "code": "123456",
    "clientType": "pc"
})
login_data = r.json()
print(f"登录响应: code={login_data.get('code')}, msg={login_data.get('msg')}")
token = login_data.get("data", {}).get("token")
if not token:
    print("登录失败，退出")
    exit(1)
print(f"Token获取成功: {token[:30]}...")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

# 3. 手动发送 - 通过通道实例id=4(阿里云短信)
print("\n=== 3. 通过通道实例(id=4)发送测试消息 ===")
print("通道实例: id=4, aliyun_sms, '设备离线通知-阿里云' (租户1)")
manual_payload = {
    "eventType": "device_offline",
    "bizId": "TEST-CH-001",
    "content": "【通道实例测试】这是一条通过阿里云短信通道实例发送的测试消息，验证通道实例→Sender→外部API完整链路。",
    "contactIds": [],
    "channels": [4]
}
r = requests.post(f"{BASE}/alert/event/manual", headers=headers, json=manual_payload)
manual_resp = r.json()
print(f"手动发送响应: {json.dumps(manual_resp, ensure_ascii=False, indent=2)}")

event_id = manual_resp.get("data")
print(f"事件ID: {event_id}")

# 4. 等待异步处理
print("\n=== 4. 等待5秒后检查发送日志 ===")
time.sleep(5)

# 查询通知日志 - 直接查最新的日志
r = requests.get(f"{BASE}/alert/log/list?pageNum=1&pageSize=5", headers=headers)
log_resp = r.json()
print(f"日志查询响应: code={log_resp.get('code')}")
if log_resp.get("data"):
    logs = log_resp["data"].get("records", log_resp["data"].get("rows", []))
    if isinstance(log_resp["data"], list):
        logs = log_resp["data"]
    print(f"最新通知日志数: {len(logs)}")
    for log_entry in logs[:5]:
        print(f"  ---")
        print(f"  日志ID: {log_entry.get('id')}")
        print(f"  事件ID: {log_entry.get('eventId')}")
        print(f"  通道类型: {log_entry.get('channelType')}")
        print(f"  接收人: {log_entry.get('receiversJson')}")
        print(f"  发送内容: {log_entry.get('content')}")
        print(f"  是否成功: {log_entry.get('success')}")
        print(f"  错误信息: {log_entry.get('errorMsg')}")
        print(f"  第三方消息ID: {log_entry.get('thirdPartyMsgId')}")
        print(f"  耗时(ms): {log_entry.get('costTime')}")
        print(f"  重试次数: {log_entry.get('retryCount')}")

# 5. 通过webhook触发租户7的设备离线通知（走pushplus通道实例id=1）
print("\n=== 5. 通过Webhook触发租户7告警（走pushplus通道实例）===")
WEBHOOK_TOKEN = "c9e8c10ed0a4480ebf0354c112bc435e"
alert_payload = {
    "version": "4",
    "groupKey": "test-channel-group",
    "status": "firing",
    "alerts": [{
        "labels": {
            "alertname": "device_offline",
            "device_code": "TEST-DEVICE-001",
            "tenant_id": "7",
            "severity": "critical"
        },
        "annotations": {
            "summary": "通道实例测试：设备离线告警",
            "description": "测试通过webhook→通道实例(pushplus id=1)完整链路"
        },
        "startsAt": "2026-09-29T10:00:00Z"
    }]
}
r = requests.post("http://192.168.1.55/alert/push", 
                   headers={"X-Netsight-Webhook-Token": WEBHOOK_TOKEN, "Content-Type": "application/json"},
                   json=alert_payload)
print(f"Webhook响应: {r.json()}")

time.sleep(5)

# 6. 再查日志
print("\n=== 6. 再次查询最新通知日志 ===")
r = requests.get(f"{BASE}/alert/log/list?pageNum=1&pageSize=5", headers=headers)
log_resp = r.json()
if log_resp.get("data"):
    logs = log_resp["data"].get("records", log_resp["data"].get("rows", []))
    if isinstance(log_resp["data"], list):
        logs = log_resp["data"]
    for log_entry in logs[:5]:
        print(f"  日志ID={log_entry.get('id')} 通道={log_entry.get('channelType')} 成功={log_entry.get('success')} 接收人={log_entry.get('receiversJson')} 错误={log_entry.get('errorMsg')}")

print("\n=== 测试完成 ===")
