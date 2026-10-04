import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=10)

sql = """
UPDATE sys_permission SET icon='link' WHERE id=140;
UPDATE sys_permission SET icon='server' WHERE id=41;
"""
sftp = ssh.open_sftp()
with sftp.file('/tmp/fix2.sql', 'w') as f:
    f.write(sql)
sftp.close()
stdin, stdout, stderr = ssh.exec_command('mysql -u sadmin -pChinaunicom@1358 netsight < /tmp/fix2.sql 2>&1')
print(stdout.read().decode('utf-8', errors='replace'))
print(stderr.read().decode('utf-8', errors='replace'))
ssh.close()
