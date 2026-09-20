import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
print(run("journalctl -u netsight-server --no-pager -n 300 | grep -iE 'exception|caused by|APPLICATION FAILED|BeanCreation|Error creating|nested exception|Caused by' | tail -40"))
print('=== 尾部原始日志 ===')
print(run("journalctl -u netsight-server --no-pager -n 40 | grep -v 'systemd\\[' "))
c.close()
