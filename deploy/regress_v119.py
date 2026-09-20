import paramiko, sys, io, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()

B = 'http://127.0.0.1/prod-api'

def login(phone):
    run(f"curl -s -X POST '{B}/auth/sms-code?phone={phone}' > /dev/null")
    body = json.dumps({"phone": phone, "code": "123456"})
    out = run(f"curl -s -X POST '{B}/auth/login' -H 'Content-Type: application/json' -d '{body}'")
    try:
        j = json.loads(out)
        return j.get('data', {}).get('token')
    except Exception:
        return None

def get(tok, path):
    return run(f"curl -s '{B}{path}' -H 'Authorization: Bearer {tok}'")
def post(tok, path, data):
    d = json.dumps(data)
    return run(f"curl -s -X POST '{B}{path}' -H 'Authorization: Bearer {tok}' -H 'Content-Type: application/json' -d '{d}'")

print('=== admin 登录 ===')
tok = login('13800000000')
print('token 长度:', len(tok) if tok else None)

print('=== 通知联系人列表 /alert/contact/list ===')
print(get(tok, '/alert/contact/list?pageNum=1&pageSize=10'))

print('=== 联系人下拉 options ===')
print(get(tok, '/alert/contact/options'))

print('=== 规则列表（确认 receiver_strategy_json 已是 contactIds） ===')
print(get(tok, '/alert/rule/list?pageNum=1&pageSize=10')[:1500])

print('=== 租户1 pushplus-token GET ===')
print(get(tok, '/system/tenant/1/pushplus-token'))

print('=== 租户1 pushplus-token SET 测试值 ===')
print(post(tok, '/system/tenant/1/pushplus-token', {"token": "test-pushplus-verify-123"}))

print('=== 租户1 pushplus-token GET（确认回写） ===')
print(get(tok, '/system/tenant/1/pushplus-token'))

c.close()
print('DONE')
