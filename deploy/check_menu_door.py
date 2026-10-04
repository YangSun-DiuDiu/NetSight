import paramiko

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

# 查询设备资产相关的菜单结构
cmd = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT menu_id,parent_id,menu_name,path,component,perms FROM sys_menu WHERE menu_name LIKE '%设备%' OR menu_name LIKE '%门禁%' OR menu_name LIKE '%网关%' ORDER BY parent_id,order_num;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd)
print("=== 菜单 ===")
print(stdout.read().decode())

# 查询现有device表中门禁类型设备
cmd2 = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT id,device_name,device_type,brand,model,ip_address,location,gateway_id,tenant_id FROM device WHERE del_flag=0 AND device_type='door_controller';" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd2)
print("=== 门禁控制器设备 ===")
print(stdout.read().decode())

# 查询全部设备的类型分布
cmd3 = r"""mysql -usadmin -p'Chinaunicom@1358' netsight -e "SELECT device_type,COUNT(*) cnt FROM device WHERE del_flag=0 GROUP BY device_type;" 2>&1"""
stdin, stdout, stderr = c.exec_command(cmd3)
print("=== 设备类型分布 ===")
print(stdout.read().decode())

c.close()
