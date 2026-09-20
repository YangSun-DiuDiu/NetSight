import paramiko
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', username='root', password='Chinaunicom@1358')

# 备份
c.exec_command("cp /etc/nginx/conf.d/nams.conf /etc/nginx/conf.d/nams.conf.bak_alertfix")

# 用 sed 把 location /alert/ 改成 location /alert/push
cmds = [
    "sed -i 's#location /alert/#location /alert/push#g' /etc/nginx/conf.d/nams.conf",
    "nginx -t",
    "systemctl reload nginx",
    "grep -A3 'alert' /etc/nginx/conf.d/nams.conf",
]
for cmd in cmds:
    _, o, e = c.exec_command(cmd)
    print("$ " + cmd)
    print(o.read().decode())
    err = e.read().decode()
    if err:
        print("ERR:", err)
c.close()
