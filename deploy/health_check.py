# -*- coding: utf-8 -*-
import paramiko, time, sys
cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=15)
ok = False
for i in range(25):
    time.sleep(4)
    _, out, _ = cli.exec_command('curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/actuator/health')
    code = out.read().decode().strip()
    if i % 3 == 0:
        print('probe', i + 1, code)
    if code == '200':
        ok = True
        break
if ok:
    _, out, _ = cli.exec_command('curl -s http://127.0.0.1:8080/actuator/health')
    print('HEALTH:', out.read().decode()[:300])
else:
    print('DOWN')
    _, out, _ = cli.exec_command('tail -40 /opt/nams-server/logs/stderr.log 2>/dev/null')
    print(out.read().decode())
cli.close()
