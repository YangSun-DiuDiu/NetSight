"""P0-1 登录认证闭环测试：4角色 + H5 + 异常场景"""
import requests, time

BASE = "http://192.168.1.55/prod-api"
results = []

def test(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'} | {name} | {detail}")

roles = [
    ("admin", "18667800006", "super_admin", 1),
    ("sunyang21", "15657477316", "tenant_admin", 7),
    ("ops_test", "15700000001", "ops", 7),
    ("repairer_test", "15700000002", "repairer", 7),
]

tokens = {}
for username, phone, role, tenant_id in roles:
    try:
        r = requests.post(f"{BASE}/auth/sms-code?phone={phone}", timeout=10)
        sms_ok = r.json().get("code") == 200
        time.sleep(0.5)

        r = requests.post(f"{BASE}/auth/login", json={
            "phone": phone, "code": "123456", "clientType": "pc"
        }, timeout=10)
        data = r.json()
        token = data.get("data", {}).get("token", "")
        tokens[username] = token
        login_ok = data.get("code") == 200 and bool(token)

        if token:
            time.sleep(0.2)
            r2 = requests.get(f"{BASE}/getInfo", headers={"Authorization": f"Bearer {token}"}, timeout=10)
            d = r2.json().get("data", {})
            user_roles = d.get("roles", [])
            user_tenant = d.get("user", {}).get("tenantId")
            role_ok = role in user_roles or (role == "super_admin" and "admin" in user_roles)
            tenant_ok = user_tenant == tenant_id
            test(f"PC登录-{username}", login_ok and sms_ok, f"roles={user_roles}, tenant={user_tenant}")
            test(f"角色-{username}", role_ok, f"期望{role}, 实际{user_roles}")
            test(f"租户-{username}", tenant_ok, f"期望{tenant_id}, 实际{user_tenant}")
        else:
            test(f"PC登录-{username}", False, f"sms={sms_ok}, resp={data}")
    except Exception as e:
        test(f"PC登录-{username}", False, str(e))
    time.sleep(1)

# H5 (等12秒避免sms-code限流)
time.sleep(12)
try:
    requests.post(f"{BASE}/auth/sms-code?phone=15700000002", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={
        "phone": "15700000002", "code": "123456", "clientType": "m"
    }, timeout=10)
    data = r.json()
    h5_token = data.get("data", {}).get("token", "")
    test("H5登录", data.get("code") == 200 and bool(h5_token), f"code={data.get('code')}, msg={data.get('msg','')}")
    if h5_token:
        r = requests.get(f"{BASE}/m/order/my-list?status=", headers={"Authorization": f"Bearer {h5_token}"}, timeout=10)
        d = r.json()
        test("H5工单列表", d.get("code") == 200, f"code={d.get('code')}, total={d.get('total','?')}")
except Exception as e:
    test("H5登录", False, str(e))

# 异常
try:
    requests.post(f"{BASE}/auth/sms-code?phone=18667800006", timeout=10)
    time.sleep(0.5)
    r = requests.post(f"{BASE}/auth/login", json={"phone": "18667800006", "code": "000000", "clientType": "pc"}, timeout=10)
    test("错误验证码拒绝", r.json().get("code") != 200, f"code={r.json().get('code')}")
except Exception as e:
    test("错误验证码拒绝", False, str(e))

try:
    r = requests.get(f"{BASE}/getInfo", timeout=10)
    test("无token拒绝", r.status_code == 401, f"status={r.status_code}")
except Exception as e:
    test("无token拒绝", False, str(e))

try:
    r = requests.get(f"{BASE}/getInfo", headers={"Authorization": "Bearer invalid"}, timeout=10)
    test("错误token拒绝", r.status_code == 401, f"status={r.status_code}")
except Exception as e:
    test("错误token拒绝", False, str(e))

# 登出
for username, token in tokens.items():
    if not token:
        test(f"登出-{username}", False, "无token")
        continue
    try:
        r = requests.post(f"{BASE}/auth/logout", headers={"Authorization": f"Bearer {token}"}, timeout=10)
        time.sleep(0.3)
        r2 = requests.get(f"{BASE}/getInfo", headers={"Authorization": f"Bearer {token}"}, timeout=10)
        test(f"登出-{username}", r.json().get("code") == 200 and r2.status_code == 401,
             f"logout={r.json().get('code')}, 登出后={r2.status_code}")
    except Exception as e:
        test(f"登出-{username}", False, str(e))

passed = sum(1 for _, ok, _ in results if ok)
failed = sum(1 for _, ok, _ in results if not ok)
print(f"\n=== 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok:
        print(f"  FAIL: {name}: {detail}")
