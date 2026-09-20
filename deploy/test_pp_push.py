import paramiko, sys, io, json, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
B='http://127.0.0.1/prod-api'
# sunyang21 租户7 登录
run(f"curl -s -X POST '{B}/auth/sms-code?phone=15657477316' > /dev/null")
time.sleep(1)
login = run(f"curl -s -X POST '{B}/auth/login' -H 'Content-Type: application/json' -d '{{\"phone\":\"15657477316\",\"code\":\"123456\"}}'")
print('login:', login[:200])
tok = json.loads(login).get('data',{}).get('token')
if not tok:
    print('LOGIN FAIL'); c.close(); sys.exit()
# 手动发送：仅 pushplus 通道，contactIds 空（pushplus 走租户 token）
body = {"eventType":"device_offline","deviceName":"大华摄像头(测试推送)","deviceIp":"192.168.1.77","channels":["pushplus"],"contactIds":[],"content":"四角色回归测试：PushPlus 真实投递验证，请确认是否收到微信推送"}
print('manual:', run(f"curl -s -X POST '{B}/alert/event/manual' -H 'Authorization: Bearer {tok}' -H 'Content-Type: application/json' -d '{json.dumps(body,ensure_ascii=False)}'"))
time.sleep(3)
print('=== 通知日志（最近 pushplus）===')
print(run(f"curl -s '{B}/alert/log/list?pageNum=1&pageSize=5' -H 'Authorization: Bearer {tok}'")[:1200])
c.close()
