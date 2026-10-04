"""P0-2 多租户隔离测试"""
import requests, time

BASE = "http://192.168.1.55/prod-api"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

def login(phone):
    requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": phone, "code": "123456", "clientType": "pc"}, timeout=10)
    return r.json().get("data", {}).get("token", "")

def get_list(token, path, params=None):
    r = requests.get(f"{BASE}{path}", headers={"Authorization": f"Bearer {token}"}, params=params or {}, timeout=10)
    j = r.json()
    d = j.get("data", {})
    # 兼容 records / rows / list
    rows = d.get("records") or d.get("rows") or d.get("list") or (d if isinstance(d, list) else [])
    total = d.get("total", len(rows) if isinstance(rows, list) else 0)
    return j.get("code"), rows, total

admin_token = login("18667800006")
time.sleep(1)
t7_token = login("15657477316")
ah = {"Authorization": f"Bearer {admin_token}"}
th = {"Authorization": f"Bearer {t7_token}"}

test("admin登录", bool(admin_token), "")
test("tenant7登录", bool(t7_token), "")

# === 设备列表 ===
code, admin_devs, admin_total = get_list(admin_token, "/device/list", {"pageNum":1,"pageSize":100})
admin_ips = [d.get("ipAddress","") for d in admin_devs]
test("admin设备列表", code==200 and len(admin_devs) >= 5, f"总数={len(admin_devs)}, IPs={admin_ips[:5]}")

code, t7_devs, t7_total = get_list(t7_token, "/device/list", {"pageNum":1,"pageSize":100})
t7_ips = [d.get("ipAddress","") for d in t7_devs]
test("tenant7设备隔离", code==200 and len(t7_devs)==2, f"总数={len(t7_devs)}, IPs={t7_ips}")
leaked = [ip for ip in t7_ips if ip not in ["192.168.1.76","192.168.1.77"]]
test("tenant7无越权设备", len(leaked)==0, f"越权={leaked}")

# === 跨租户设备详情 ===
t1_dev = next((d for d in admin_devs if d.get("ipAddress") not in ["192.168.1.76","192.168.1.77"]), None)
if t1_dev:
    r = requests.get(f"{BASE}/device/{t1_dev['id']}", headers=th, timeout=10)
    j = r.json()
    test("跨租户设备详情被拒", j.get("code") != 200 or "不存在" in j.get("msg",""),
         f"code={j.get('code')}, msg={j.get('msg','')}")

# === 工单列表 ===
code, admin_orders, _ = get_list(admin_token, "/workorder/order/list", {"pageNum":1,"pageSize":100})
test("admin工单列表", code==200 and len(admin_orders)>=1, f"code={code}, 总数={len(admin_orders)}")

code, t7_orders, _ = get_list(t7_token, "/workorder/order/list", {"pageNum":1,"pageSize":100})
t7_tenants = set(o.get("tenantId") for o in t7_orders if isinstance(o,dict))
test("tenant7工单隔离", code==200 and t7_tenants.issubset({7}) and len(t7_orders)>=1,
     f"总数={len(t7_orders)}, tenants={t7_tenants}")

# === 告警事件 ===
code, admin_events, _ = get_list(admin_token, "/alert/event/list", {"pageNum":1,"pageSize":100})
test("admin告警事件", code==200 and len(admin_events)>=1, f"code={code}, 总数={len(admin_events)}")

code, t7_events, _ = get_list(t7_token, "/alert/event/list", {"pageNum":1,"pageSize":100})
t7_event_tenants = set(e.get("tenantId") for e in t7_events if isinstance(e,dict))
test("tenant7告警事件隔离", code==200 and t7_event_tenants.issubset({7,None}),
     f"总数={len(t7_events)}, tenants={t7_event_tenants}")

# === 知识库 ===
code, t7_know, _ = get_list(t7_token, "/knowledge/fault/list", {"pageNum":1,"pageSize":100})
test("tenant7知识库隔离", code==200 and len(t7_know)==0, f"总数={len(t7_know)}（应为0，tenant1预制数据不可见）")

# === 网关列表 ===
code, t7_gws, _ = get_list(t7_token, "/device/gateway/list", {"pageNum":1,"pageSize":100})
t7_gw_tenants = set(g.get("tenantId") for g in t7_gws if isinstance(g,dict))
test("tenant7网关隔离", code==200 and t7_gw_tenants.issubset({7,None}), f"总数={len(t7_gws)}, tenants={t7_gw_tenants}")

# === 备件 ===
code, t7_spares, _ = get_list(t7_token, "/spare/part/list", {"pageNum":1,"pageSize":100})
t7_sp_tenants = set(s.get("tenantId") for s in t7_spares if isinstance(s,dict))
test("tenant7备件隔离", code==200 and t7_sp_tenants.issubset({7,None}), f"总数={len(t7_spares)}, tenants={t7_sp_tenants}")

# 登出
for t in [admin_token, t7_token]:
    if t: requests.post(f"{BASE}/auth/logout", headers={"Authorization":f"Bearer {t}"}, timeout=10)

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P0-2 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
