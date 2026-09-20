import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
print('=== error.log 里 Exception 行 ===')
print(run("grep -a Exception /opt/nams-server/logs/netsight-error.log | tail -15"))
print('=== caused 行 ===')
print(run("grep -a Caused /opt/nams-server/logs/netsight-error.log | tail -15"))
print('=== tenant 表 pushplus 列 ===')
print(run("mysql -usadmin -pChinaunicom@1358 netsight -e 'SHOW COLUMNS FROM sys_tenant' 2>/dev/null | grep -i pushplus"))
c.close()
