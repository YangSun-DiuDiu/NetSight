"""P0-4 工单闭环（租户7内完整流程）"""
import requests, time, json

BASE = "http://192.168.1.55/prod-api"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

def login(phone, ct="pc"):
    requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": phone, "code": "123456", "clientType": ct}, timeout=10)
    return r.json()

# 租户7管理员创建工单
jr = login("15657477316")
t7_token = jr.get("data",{}).get("token","")
th = {"Authorization": f"Bearer {t7_token}"}
test("租户7管理员登录", bool(t7_token), "")

r = requests.post(f"{BASE}/workorder/order", 
    json={"deviceCode":"DEV-TEST-T7","title":"P0-4租户7测试工单","faultType":"offline","priority":"high","description":"闭环测试","location":"测试"},
    headers=th, timeout=10)
oid = r.json().get("data")
test("租户7创建工单", r.json().get("code")==200 and oid, f"orderId={oid}")

r = requests.post(f"{BASE}/workorder/order/dispatch",
    json={"id": oid, "repairerId": 8, "remark": "测试派单"},
    headers=th, timeout=15)
test("租户7派单给维修人员", r.json().get("code")==200, f"code={r.json().get('code')}, msg={r.json().get('msg')}")

time.sleep(1)

# H5维修人员登录
time.sleep(2)
jr2 = login("15700000002", "m")
repairer_token = jr2.get("data",{}).get("token","")
rh = {"Authorization": f"Bearer {repairer_token}"}
test("H5维修人员登录", jr2.get("code")==200 and bool(repairer_token), f"msg={jr2.get('msg','')}")

if repairer_token and oid:
    r = requests.get(f"{BASE}/m/order/my-list?status=1", headers=rh, timeout=10)
    test("H5待接单列表", r.json().get("code")==200, f"code={r.json().get('code')}")
    
    r = requests.post(f"{BASE}/m/order/{oid}/start", headers=rh, timeout=10)
    test("H5开工", r.json().get("code")==200, f"code={r.json().get('code')}, msg={r.json().get('msg','')}")
    
    time.sleep(1)
    
    r = requests.post(f"{BASE}/m/order/{oid}/complete",
        json={"repairResult":"维修完成，设备已恢复","photos":["/uploads/test.jpg"],"parts":[]},
        headers=rh, timeout=10)
    test("H5完工", r.json().get("code")==200, f"code={r.json().get('code')}, msg={r.json().get('msg','')}")
    
    time.sleep(1)
    
    # 验证状态
    r = requests.get(f"{BASE}/workorder/order/{oid}", headers=th, timeout=10)
    order_data = r.json().get("data",{})
    order = order_data.get("order", order_data) if isinstance(order_data, dict) else {}
    test("工单已完成(status=3)", order.get("status") in [3,"3"], f"status={order.get('status')}")

for t in [t7_token, repairer_token]:
    if t: requests.post(f"{BASE}/auth/logout", headers={"Authorization":f"Bearer {t}"}, timeout=10)

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P0-4 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
