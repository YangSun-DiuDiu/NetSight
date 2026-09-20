# -*- coding: utf-8 -*-
"""查手动发送日志content"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=15)
cmd = "mysql -usadmin -pChinaunicom@1358 netsight -e \"SELECT id, event_id, channel_type, LEFT(content,60) AS content, success FROM notification_log WHERE id >= 5;\""
si, so, se = ssh.exec_command(cmd, timeout=30)
print(so.read().decode('utf-8', 'ignore'))
print('ERR:', se.read().decode('utf-8', 'ignore')[:300])
ssh.close()
