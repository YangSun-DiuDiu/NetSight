import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')
cmd = "grep -oE 'chunk-[a-z0-9]+\\.[a-z0-9]+\\.js' /opt/nams-ui/dist/index.html | head -10"
_, o, _ = c.exec_command(cmd)
print(o.read().decode())
c.close()
