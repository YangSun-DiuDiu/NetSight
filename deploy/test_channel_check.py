import paramiko, sys

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

# 1. 查询通道实例
cmd = """mysql -usadmin -pChinaunicom@1358 netsight -e "SELECT id,tenant_id,channel_type,name,enabled FROM notify_channel;" 2>/dev/null"""
stdin, stdout, stderr = c.exec_command(cmd)
print("=== notify_channel ===")
print(stdout.read().decode())

# 2. 查询通知模板
cmd2 = """mysql -usadmin -pChinaunicom@1358 netsight -e "SELECT id,tenant_id,template_code,channel_type,channel_id,enabled FROM notification_template WHERE enabled=1;" 2>/dev/null"""
stdin, stdout, stderr = c.exec_command(cmd2)
print("=== notification_template (enabled) ===")
print(stdout.read().decode())

c.close()
