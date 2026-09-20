import paramiko, sys, io, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()

local = r'E:\gitee\NetSight1.0\netsight-server\target\netsight-server.jar'
print('本地 jar 大小:', os.path.getsize(local))
print('远端现有 jar 大小:', run('ls -l /opt/nams-server/netsight-server.jar'))

# 备份旧 jar，重传
run('cp /opt/nams-server/netsight-server.jar /opt/nams-server/netsight-server.jar.bak_corrupt 2>/dev/null; true')
sftp = c.open_sftp()
sftp.put(local, '/opt/nams-server/netsight-server.jar')
st = sftp.stat('/opt/nams-server/netsight-server.jar')
print('上传后远端大小:', st.st_size)
sftp.close()

print('== 校验 jar ==')
print(run('cd /opt/nams-server && /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar netsight-server.jar --version 2>&1 | head -3 || echo "jar-check-fail"'))
print('== 重启服务 ==')
print(run('systemctl restart netsight-server; sleep 15; systemctl is-active netsight-server'))
print('== health ==')
print(run('curl -s http://127.0.0.1:8080/actuator/health'))
c.close()
