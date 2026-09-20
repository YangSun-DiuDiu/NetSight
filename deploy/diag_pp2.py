import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
print('=== 重新触发一次 set，立即抓日志 ===')
# 先登录拿 token
run("curl -s -X POST 'http://127.0.0.1/prod-api/auth/sms-code?phone=13800000000' > /dev/null")
login = run("curl -s -X POST 'http://127.0.0.1/prod-api/auth/login' -H 'Content-Type: application/json' -d '{\"phone\":\"13800000000\",\"code\":\"123456\"}'")
import json
tok = json.loads(login).get('data',{}).get('token')
print('set 响应:', run("curl -s -X PUT 'http://127.0.0.1/prod-api/system/tenant/1/pushplus-token' -H 'Authorization: Bearer %s' -H 'Content-Type: application/json' -d '{\"token\":\"verify-test-123\"}'" % tok))
print('=== stdout.log 尾部 ===')
print(run("tail -40 /opt/nams-server/logs/stdout.log | grep -aiE 'pushplus|exception|error|caused|SQL|column|Field' | tail -30"))
c.close()
