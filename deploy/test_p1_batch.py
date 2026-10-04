"""P1 批量功能测试：设备/备件/点检/知识/公告/待办/系统/大屏"""
import requests, time, json

BASE = "http://192.168.1.55/prod-api"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

def login(phone):
    requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": phone, "code": "123456", "clientType": "pc"}, timeout=10)
    return r.json().get("data",{}).get("token","")

token = login("18667800006")
ah = {"Authorization": f"Bearer {token}"}
test("admin登录", bool(token), "")

def get(path, name):
    r = requests.get(f"{BASE}{path}", headers=ah, timeout=10)
    d = r.json().get("data",{})
    rows = d.get("rows", d) if isinstance(d, dict) else d
    test(name, r.json().get("code")==200, f"code={r.json().get('code')}, count={len(rows) if isinstance(rows,list) else 'N/A'}")
    return r.json()

# P1-6 设备资产管理
get("/device/list?pageNum=1&pageSize=5", "设备列表")
get("/device/qrcode/1", "设备二维码接口")

# P1-7 备品备件
get("/spare/part/list?pageNum=1&pageSize=5", "备件列表")
get("/spare/stock/list?pageNum=1&pageSize=5", "库存列表")

# P1-8 点检巡检
get("/inspection/task/list?pageNum=1&pageSize=5", "点检任务列表")
get("/inspection/plan/list?pageNum=1&pageSize=5", "点检计划列表")

# P1-9 知识库
get("/knowledge/fault/list?pageNum=1&pageSize=5", "故障知识库列表")

# P1-10 通知公告
get("/notice/list?pageNum=1&pageSize=5", "公告列表")
get("/notice/published", "已发布公告")

# P1-11 统一待办
get("/todo/stats", "待办统计")
get("/todo/list?pageNum=1&pageSize=5", "待办列表")

# P1-12 系统管理
get("/system/user/list?pageNum=1&pageSize=5", "用户列表")
get("/system/role/list?pageNum=1&pageSize=5", "角色列表")
get("/system/tenant/list?pageNum=1&pageSize=5", "租户列表")

# P1-13 监控大屏
get("/dashboard/summary", "大屏汇总数据")

# 4角色权限验证
time.sleep(1)
t7_token = login("15657477316")
t7h = {"Authorization": f"Bearer {t7_token}"}
r = requests.get(f"{BASE}/device/list?pageNum=1&pageSize=50", headers=t7h, timeout=10)
t7_devices = r.json().get("data",{}).get("rows",[])
test("租户7设备隔离", all(d.get("tenantId")==7 for d in t7_devices), f"设备数={len(t7_devices)}")

# 维修人员权限
time.sleep(1)
rp_token = login("15700000002")
rph = {"Authorization": f"Bearer {rp_token}"}
r = requests.get(f"{BASE}/device/list", headers=rph, timeout=10)
test("维修人员无法看设备", r.json().get("code") in [403,500], f"code={r.json().get('code')}")

r = requests.get(f"{BASE}/workorder/order/list?pageNum=1&pageSize=5", headers=rph, timeout=10)
test("维修人员可看工单", r.json().get("code")==200, f"code={r.json().get('code')}")

for t in [token, t7_token, rp_token]:
    if t: requests.post(f"{BASE}/auth/logout", headers={"Authorization":f"Bearer {t}"}, timeout=10)

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P1 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
