"""P0-5 边缘网关通信测试"""
import requests, time, json, paramiko

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

# 1. admin登录
token = login("18667800006")
ah = {"Authorization": f"Bearer {token}"}
test("admin登录", bool(token), "")

# 2. 网关列表
r = requests.get(f"{BASE}/edge/gateway/list?pageNum=1&pageSize=20", headers=ah, timeout=10)
gws = r.json().get("data",{}).get("rows",[])
test("网关列表", r.json().get("code")==200 and len(gws)>=1, f"网关数={len(gws)}")

# 3. 网关在线状态
if gws:
    online = [g for g in gws if g.get("onlineStatus")==1]
    test("网关在线状态", len(online)>=1, f"在线={len(online)}/{len(gws)}, names={[g.get('gatewayName') for g in online]}")
    
    # 4. 最近心跳时间
    gw = gws[0]
    test("网关最近心跳", bool(gw.get("lastHeartbeatTime")), f"lastHeartbeat={gw.get('lastHeartbeatTime')}")

# 5. 直接从边缘网关(.60)测试心跳上报
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.60', username='root', password='Chinaunicom@1358')

_, o, _ = c.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/actuator/health 2>/dev/null || curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/ 2>/dev/null')
health_code = o.read().decode().strip()
test("nams-agent健康检查", health_code in ["200","404"], f"HTTP {health_code}")

# 6. 检查prometheus是否在运行
_, o, _ = c.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/-/healthy 2>/dev/null')
prom_code = o.read().decode().strip()
test("Prometheus运行状态", prom_code=="200", f"HTTP {prom_code}")

# 7. 检查alertmanager
_, o, _ = c.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:9093/-/healthy 2>/dev/null')
am_code = o.read().decode().strip()
test("Alertmanager运行状态", am_code=="200", f"HTTP {am_code}")

# 8. 检查blackbox
_, o, _ = c.exec_command('curl -s -o /dev/null -w "%{http_code}" http://localhost:9115/-/healthy 2>/dev/null')
bb_code = o.read().decode().strip()
test("Blackbox exporter运行状态", bb_code in ["200","404"], f"HTTP {bb_code}")

c.close()

# 9. 设备状态上报接口（无token应401）
r = requests.post(f"{BASE}/edge/report/status", json={"deviceCode":"TEST","status":1}, timeout=10)
test("设备状态上报无token拒绝", r.json().get("code") in [401,500], f"code={r.json().get('code')}")

# 10. 设备列表中状态字段
r = requests.get(f"{BASE}/device/list?pageNum=1&pageSize=5", headers=ah, timeout=10)
devices = r.json().get("data",{}).get("rows",[])
test("设备状态字段", len(devices)>=1 and "status" in devices[0], 
     f"设备数={len(devices)}, sample status={devices[0].get('status') if devices else 'N/A'}")

requests.post(f"{BASE}/auth/logout", headers=ah, timeout=10)

passed = sum(1 for _,ok,_ in results if ok)
failed = sum(1 for _,ok,_ in results if not ok)
print(f"\n=== P0-5 汇总: {passed} PASS / {failed} FAIL / 共 {len(results)} 项 ===")
for name, ok, detail in results:
    if not ok: print(f"  FAIL: {name}: {detail}")
