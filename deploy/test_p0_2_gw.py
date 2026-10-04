import requests, time

BASE = "http://192.168.1.55/prod-api"
def login(phone):
    requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": phone, "code": "123456", "clientType": "pc"}, timeout=10)
    return r.json().get("data",{}).get("token","")

admin_token = login("18667800006")
time.sleep(1)
t7_token = login("15657477316")

# admin 看全部网关
r = requests.get(f"{BASE}/edge/gateway/list?pageNum=1&pageSize=100", headers={"Authorization":f"Bearer {admin_token}"}, timeout=10)
admin_gws = r.json().get("data",{}).get("rows",[])
print(f"admin网关: {len(admin_gws)}个, tenants={set(g.get('tenantId') for g in admin_gws)}")

# tenant7 只看自己
r = requests.get(f"{BASE}/edge/gateway/list?pageNum=1&pageSize=100", headers={"Authorization":f"Bearer {t7_token}"}, timeout=10)
t7_gws = r.json().get("data",{}).get("rows",[])
t7_tenants = set(g.get("tenantId") for g in t7_gws)
print(f"tenant7网关: {len(t7_gws)}个, tenants={t7_tenants}")
ok = t7_tenants.issubset({7}) and len(t7_gws) >= 1
print(f"PASS | tenant7网关隔离" if ok else f"FAIL | tenant7网关隔离: tenants={t7_tenants}")

for t in [admin_token, t7_token]:
    if t: requests.post(f"{BASE}/auth/logout", headers={"Authorization":f"Bearer {t}"}, timeout=10)
