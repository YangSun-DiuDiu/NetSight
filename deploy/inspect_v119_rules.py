import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=15)

def run(cmd):
    stdin, stdout, stderr = c.exec_command(cmd)
    out = stdout.read().decode('utf-8', 'replace')
    err = stderr.read().decode('utf-8', 'replace')
    return out, err

sql = ("SELECT * FROM notification_rule WHERE del_flag=0 ORDER BY id LIMIT 3;")
cmd = 'mysql -usadmin -pChinaunicom@1358 netsight --default-character-set=utf8mb4 -e "%s"' % sql
out, err = run(cmd)
print("=== notification_rule sample ===")
print(out)
if err.strip():
    print("ERR:", err)
c.close()
