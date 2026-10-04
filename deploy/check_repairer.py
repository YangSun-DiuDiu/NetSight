import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=10)

sql = """
SELECT '=== repairer table ===' AS info;
SELECT id, name, phone, tenant_id, status FROM repairer WHERE phone='15657477316';
SELECT '=== sys_user table ===' AS info;
SELECT id, username, phone, tenant_id FROM sys_user WHERE phone='15657477316';
SELECT '=== sys_user_role ===' AS info;
SELECT ur.user_id, ur.role_id, r.role_key FROM sys_user_role ur JOIN sys_role r ON ur.role_id=r.id JOIN sys_user u ON ur.user_id=u.id WHERE u.phone='15657477316';
SELECT '=== all repairers ===' AS info;
SELECT id, name, phone, tenant_id, status FROM repairer;
"""
stdin, stdout, stderr = ssh.exec_command(f'mysql -usadmin -pChinaunicom@1358 netsight -e "{sql}"')
print(stdout.read().decode())
err = stderr.read().decode()
if err:
    print("STDERR:", err)
ssh.close()
