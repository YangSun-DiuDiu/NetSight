import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

cmd = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SHOW TABLES LIKE '%menu%';" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd)
print("=== menu相关表 ===")
print(stdout.read().decode())

# 列出所有表名，找菜单/权限相关
cmd2 = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SHOW TABLES;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd2)
print("=== 全部表 ===")
print(stdout.read().decode())

c.close()
