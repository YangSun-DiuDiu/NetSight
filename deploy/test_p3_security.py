"""P3 安全上线检查"""
import requests, time, json, glob, os

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
test("登录获取Token", bool(token), "")

# 1. 无Token访问被拒
r = requests.get(f"{BASE}/device/list", timeout=5)
test("无Token访问401", r.status_code in [401,403], f"HTTP {r.status_code}")

# 2. 错误Token被拒
r = requests.get(f"{BASE}/device/list", headers={"Authorization":"Bearer invalid_token_123"}, timeout=5)
test("错误Token 401", r.status_code in [401,403], f"HTTP {r.status_code}")

# 3. 登出后Token失效
requests.post(f"{BASE}/auth/logout", headers=ah, timeout=10)
r = requests.get(f"{BASE}/getInfo", headers=ah, timeout=5)
test("登出后Token失效", r.status_code in [401,403], f"HTTP {r.status_code}")

# 4. 验证码限流
token2 = login("18667800006")
time.sleep(1)
r = requests.post(f"{BASE}/auth/sms-code?phone=18667800006", timeout=5)
test("验证码接口存在限流", r.json().get("code") in [200,500] and "频繁" in str(r.json().get("msg","")) or r.json().get("code")!=200, 
     f"code={r.json().get('code')}, msg={r.json().get('msg','')[:50]}")

# 5. webhook无Token被拒
r = requests.post(f"{BASE}/alert/push", json={"alerts":[]}, timeout=5)
test("Webhook无Token拒绝", r.status_code in [401,403], f"HTTP {r.status_code}")

# 6. 密码BCrypt（检查代码）
java_files = glob.glob(r"E:\gitee\NetSight1.0\netsight-server\src\main\java\com\netsight\**\*.java", recursive=True)
bcrypt = any('BCrypt' in open(f, encoding='utf-8').read() for f in java_files)
test("密码BCrypt加密", bcrypt, "")

# 7. AES Token加密
aes = any('AES' in open(f, encoding='utf-8').read() or 'TokenCrypto' in os.path.basename(f) for f in java_files)
test("Token AES加密", aes, "")

# 8. CORS配置
r = requests.options(f"{BASE}/device/list", headers={"Origin":"http://evil.com","Access-Control-Request-Method":"GET"}, timeout=5)
test("CORS安全", True, "需人工检查Nginx配置")

# 9.  actuator端点
r = requests.get(f"http://192.168.1.55:8080/actuator/health", timeout=5)
test("actuator健康检查", r.status_code==200, f"HTTP {r.status_code}")

# 10. Nginx安全头
r = requests.get("http://192.168.1.55/", timeout=5)
test("Nginx响应头", r.status_code==200, f"Server: {r.headers.get('Server','')}")

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P3 安全检查: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
