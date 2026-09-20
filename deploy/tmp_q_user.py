# -*- coding: utf-8 -*-
import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)
cmd = "mysql -usadmin -p'Chinaunicom@1358' --default-character-set=utf8mb4 -N -e \"SELECT id,username,real_name,phone,tenant_id,status FROM netsight.sys_user WHERE del_flag=0 ORDER BY id\""
stdin, stdout, stderr = ssh.exec_command(cmd, timeout=15)
print("STDOUT:\n", stdout.read().decode("utf-8", "ignore"))
print("STDERR:\n", stderr.read().decode("utf-8", "ignore"))
ssh.close()
