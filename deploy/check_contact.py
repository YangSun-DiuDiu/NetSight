import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')
cmd = """grep -l 'data.rows' /opt/nams-ui/dist/static/js/chunk-*.js 2>/dev/null | head -5; echo ===; ls -la /opt/nams-ui/dist/static/js/chunk-5a90f05c* 2>/dev/null; echo ===; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/alert/contact"""
_, o, _ = c.exec_command(cmd)
print(o.read().decode())
c.close()
