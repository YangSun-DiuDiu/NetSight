import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
print(run("ls -la /opt/nams-server/logs/"))
print('=== stdout tail ===')
print(run("tail -30 /opt/nams-server/logs/stdout.log"))
print('=== tenant 表是否有 pushplus_token 列 ===')
print(run("mysql -usadmin -pChinaunicom@1358 netsight -e 'SHOW COLUMNS FROM sys_tenant LIKE \"%pushplus%\";' 2>/dev/null"))
c.close()
