import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

cmds = [
    "mysql -usadmin -p'Chinaunicom@1358' netsight -e 'DESCRIBE notify_channel;' 2>&1",
    "mysql -usadmin -p'Chinaunicom@1358' netsight -e 'SELECT id,tenant_id,channel_type,channel_name,enabled FROM notify_channel;' 2>&1",
]
for cmd in cmds:
    stdin, stdout, stderr = c.exec_command(cmd)
    print(f"$ {cmd}")
    print(stdout.read().decode())
    print("---")

c.close()
