import paramiko, sys, io, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
B='http://127.0.0.1/prod-api'
run(f"curl -s -X POST '{B}/auth/sms-code?phone=13800000000' > /dev/null")
login = run(f"curl -s -X POST '{B}/auth/login' -H 'Content-Type: application/json' -d '{{\"phone\":\"13800000000\",\"code\":\"123456\"}}'")
tok = json.loads(login)['data']['token']
print('PUT 配置:', run(f"curl -s -X PUT '{B}/system/tenant/1/pushplus-token' -H 'Authorization: Bearer {tok}' -H 'Content-Type: application/json' -d '{{\"token\":\"verify-test-123\"}}'"))
print('GET 回读:', run(f"curl -s '{B}/system/tenant/1/pushplus-token' -H 'Authorization: Bearer {tok}'"))
print('DELETE 清空:', run(f"curl -s -X DELETE '{B}/system/tenant/1/pushplus-token' -H 'Authorization: Bearer {tok}'"))
print('GET 清空后:', run(f"curl -s '{B}/system/tenant/1/pushplus-token' -H 'Authorization: Bearer {tok}'"))
c.close()
