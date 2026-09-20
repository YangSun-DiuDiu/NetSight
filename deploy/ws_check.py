# -*- coding: utf-8 -*-
import paramiko
cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=15)
# --max-time 3：若返回 101 说明 ws 握手成功（curl 因 upgrade 后无数据会 hang，用超时兜底）
_, out, err = cli.exec_command('curl -s -o /dev/null -w "%{http_code}" --max-time 3 -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" http://127.0.0.1:8080/ws/push; echo " rc=$?"')
print(out.read().decode())
cli.close()
