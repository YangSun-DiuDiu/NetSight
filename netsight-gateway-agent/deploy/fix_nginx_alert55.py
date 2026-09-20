# -*- coding: utf-8 -*-
"""修复 .55 Nginx /alert/ proxy_pass（去尾部斜杠，保留 /alert/ 前缀）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password=os.environ.get("GW55_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

conf = run("cat /etc/nginx/conf.d/nams.conf")
old = "location /alert/ {\n        proxy_pass http://127.0.0.1:8080/;"
new = "location /alert/ {\n        proxy_pass http://127.0.0.1:8080;"
if old in conf:
    conf = conf.replace(old, new, 1)
    sftp = ssh.open_sftp()
    with sftp.open("/etc/nginx/conf.d/nams.conf", "w") as f:
        f.write(conf)
    sftp.close()
    print(run("nginx -t 2>&1 | tail -1"))
    print(run("systemctl reload nginx && echo reload-ok"))
else:
    print("未找到需修改的内容，当前 conf：")
    print(conf)

print("\n== 验证（带 token 从 .55 本机） ==")
print(run("curl -s -o /dev/null -w 'no-token: %{http_code}\\n' -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'"))
print(run("curl -s -w '\\nwith-token: %{http_code}\\n' -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -H 'X-Netsight-Webhook-Token: 63c50a35b31943b497355daa5b988f2b' -d '{}'"))

ssh.close()
