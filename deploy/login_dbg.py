import paramiko, sys, io, json, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
B='http://127.0.0.1/prod-api'
print('sms:', run(f"curl -s -X POST '{B}/auth/sms-code?phone=13800000000'"))
time.sleep(1)
print('login resp:', run(f"curl -s -X POST '{B}/auth/login' -H 'Content-Type: application/json' -d '{{\"phone\":\"13800000000\",\"code\":\"123456\"}}'"))
c.close()
