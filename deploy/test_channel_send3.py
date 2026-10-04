"""
补测：手动发送阿里云短信（带接收人手机号）
"""
import requests, json, time

BASE = "http://192.168.1.55/prod-api"

# 登录
requests.post(f"{BASE}/auth/sms-code?phone=18667800006")
time.sleep(0.5)
r = requests.post(f"{BASE}/auth/login", json={
    "phone": "18667800006", "code": "123456", "clientType": "pc"
})
token = r.json()["data"]["token"]
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
print(f"登录成功")

# 先查询联系人列表
print("\n=== 查询联系人列表 ===")
r = requests.get(f"{BASE}/alert/contact/list?pageNum=1&pageSize=20", headers=headers)
contacts_resp = r.json()
print(f"联系人查询: code={contacts_resp.get('code')}")
if contacts_resp.get("data"):
    contacts = contacts_resp["data"].get("records", contacts_resp["data"].get("rows", []))
    if isinstance(contacts_resp["data"], list):
        contacts = contacts_resp["data"]
    for c in contacts[:10]:
        print(f"  id={c.get('id')} name={c.get('name')} mobile={c.get('mobile')}")

# 手动发送 - 带联系人（阿里云短信需要手机号）
print("\n=== 手动发送：通道实例id=4(aliyun_sms) + 联系人 ===")
manual_payload = {
    "eventType": "manual_test",
    "bizId": "TEST-CH-002",
    "content": "【通道实例补测】这是一条通过阿里云短信通道实例(id=4)手动发送的测试消息，验证：通道实例配置加载→解密→Sender→阿里云API完整链路。",
    "contactIds": [1],
    "channels": [4]
}
r = requests.post(f"{BASE}/alert/event/manual", headers=headers, json=manual_payload)
manual_resp = r.json()
print(f"响应: {json.dumps(manual_resp, ensure_ascii=False)}")
event_id = manual_resp.get("data")
print(f"事件ID: {event_id}")

time.sleep(5)

# 查日志
print("\n=== 查询最新通知日志 ===")
r = requests.get(f"{BASE}/alert/log/list?pageNum=1&pageSize=5", headers=headers)
log_resp = r.json()
if log_resp.get("data"):
    logs = log_resp["data"].get("records", log_resp["data"].get("rows", []))
    if isinstance(log_resp["data"], list):
        logs = log_resp["data"]
    for log_entry in logs[:5]:
        print(f"  日志ID={log_entry.get('id')} 事件ID={log_entry.get('eventId')} "
              f"通道={log_entry.get('channelType')} 成功={log_entry.get('success')} "
              f"接收人={log_entry.get('receiversJson')} 错误={log_entry.get('errorMsg')} "
              f"第三方ID={log_entry.get('thirdPartyMsgId')} 耗时={log_entry.get('costTime')}ms")

print("\n=== 完成 ===")
