import paramiko, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()
print('=== unit 文件 ===')
print(run('cat /etc/systemd/system/netsight-server.service'))
print('=== 手动前台跑 jar 看错误 ===')
print(run('cd /opt/nams-server && set -a && . ./nams.env 2>/dev/null; set +a; timeout 25 /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar netsight-server.jar 2>&1 | grep -iE "exception|caused by|FAILED|error|APPLICATION" | head -30'))
c.close()
