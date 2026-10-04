import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

# 查看权限表结构
cmd = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "DESCRIBE sys_permission;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd)
print("=== sys_permission结构 ===")
print(stdout.read().decode())

# 查看设备资产相关权限
cmd2 = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT id,parent_id,perm_name,perm_key,menu_type,sort FROM sys_permission WHERE perm_name LIKE '%设备%' OR perm_name LIKE '%门禁%' ORDER BY parent_id,sort;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd2)
print("=== 设备相关权限 ===")
print(stdout.read().decode())

c.close()
