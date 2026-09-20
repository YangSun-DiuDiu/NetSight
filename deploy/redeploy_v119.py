import paramiko, sys, io, os, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=20)
def run(cmd):
    i,o,e = c.exec_command(cmd); return (o.read().decode('utf-8','ignore')+e.read().decode('utf-8','ignore')).strip()

# 1) 重启后端（正确服务名 netsight-server）
print('== 重启 netsight-server ==')
print(run('systemctl restart netsight-server; sleep 8; systemctl is-active netsight-server'))
print(run("curl -s http://127.0.0.1:8080/actuator/health"))

# 2) 重新上传 dist.tar.gz
print('== sftp 上传 dist.tar.gz ==')
sftp = c.open_sftp()
local = r'E:\gitee\NetSight1.0\netsight-ui\dist.tar.gz'
remote = '/tmp/dist.tar.gz'
sftp.put(local, remote)
st = sftp.stat(remote)
print('remote size:', st.st_size)
sftp.close()

# 3) 完整解压
print('== 清理并解压 ==')
print(run('rm -rf /opt/nams-ui/dist && mkdir -p /opt/nams-ui'))
print(run('tar -xzf /tmp/dist.tar.gz -C /opt/nams-ui && echo TAR_OK'))
print(run('ls -l /opt/nams-ui/dist/index.html'))
print(run('ls /opt/nams-ui/dist/static/js/ | grep -i contact || echo "no-contact-chunk"'))
print(run("grep -o 'pushplus-token' /opt/nams-ui/dist/static/js/app.*.js | head -1"))
print(run("grep -o 'contactOptions' /opt/nams-ui/dist/static/js/*.js | head -1"))
print('== reload nginx ==')
print(run('nginx -t 2>&1 && systemctl reload nginx && echo NGINX_OK'))
print('== curl 首页 ==')
print(run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/"))
print('== curl prod-api health ==')
print(run("curl -s http://127.0.0.1/prod-api/actuator/health"))
c.close()
