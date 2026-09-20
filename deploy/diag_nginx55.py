# -*- coding: utf-8 -*-
"""查看 nams.conf 内容"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)
stdin, stdout, stderr = ssh.exec_command("cat /etc/nginx/conf.d/nams.conf", timeout=15)
print(stdout.read().decode("utf-8", "ignore"))
ssh.close()
