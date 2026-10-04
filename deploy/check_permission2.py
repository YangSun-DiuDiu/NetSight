import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

# 查看设备资产相关权限（用正确的列名）
cmd = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT id,parent_id,perm_name,perm_key,perm_type,sort,path,component,icon FROM sys_permission WHERE perm_name LIKE '%设备%' OR perm_name LIKE '%门禁%' OR perm_name LIKE '%网关%' ORDER BY parent_id,sort;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd)
print("=== 设备/门禁/网关相关权限 ===")
print(stdout.read().decode())

# 顶级菜单
cmd2 = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT id,parent_id,perm_name,perm_key,perm_type,sort,path,component,icon FROM sys_permission WHERE parent_id=0 ORDER BY sort;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd2)
print("=== 顶级菜单 ===")
print(stdout.read().decode())

c.close()
