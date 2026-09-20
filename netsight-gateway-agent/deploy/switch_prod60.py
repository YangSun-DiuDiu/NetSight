# -*- coding: utf-8 -*-
"""真实生产环境切换：config.json(prometheus+真实token) + Nginx /alert/ 反代"""
import paramiko
import os
import time

print("== A) .60：上传 config.json + 重启 nams-agent ==")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

sftp = ssh.open_sftp()
sftp.put(r"E:\gitee\NetSight1.0\netsight-gateway-agent\deploy\prod\config.json", "/opt/nams-gateway/conf/config.json")
sftp.close()
run("systemctl restart nams-agent")
time.sleep(6)
print("nams-agent:", run("systemctl is-active nams-agent"))
print(run("grep -oE '(source=[a-z]+|清单已同步[^\"]*)' /dev/null 2>/dev/null; journalctl -u nams-agent --since '40 seconds ago' --no-pager | grep -E 'source|数据源|清单|Prometheus|prometheus' | tail -3"))
ssh.close()

print("\n== B) .55：Nginx 加 /alert/ 反代 ==")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

conf = run("cat /etc/nginx/conf.d/nams.conf")
if "/alert/" in conf:
    print("nams.conf 已含 /alert/，跳过")
else:
    alert_loc = """    location /alert/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

"""
    # 在 /edge/ location 块后插入 /alert/（找 "location /edge/" 的下一个 "}" 之后）
    idx = conf.find("location /edge/")
    if idx == -1:
        print("!! 未找到 /edge/ location，跳过插入")
    else:
        end = conf.find("}", idx)
        if end == -1:
            print("!! 解析失败")
        else:
            new_conf = conf[:end+1] + "\n" + alert_loc + conf[end+1:]
            sftp = ssh.open_sftp()
            with sftp.open("/etc/nginx/conf.d/nams.conf", "w") as f:
                f.write(new_conf)
            sftp.close()
            print("nginx -t:", run("nginx -t 2>&1 | tail -2"))
            print("reload:", run("nginx -s reload && echo OK"))

print("\n== C) 验证 /alert/ 反代 ==")
print(run("curl -s -o /dev/null -w '%{http_code}' --max-time 8 -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'"))
ssh.close()

print("\n== D) 等 40s 让 nams-agent 上报真实状态 ==")
time.sleep(40)
ssh2 = paramiko.SSHClient()
ssh2.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh2.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
stdin, stdout, stderr = ssh2.exec_command("journalctl -u nams-agent --since '2 minutes ago' --no-pager | grep -E '快照|上报|转发|405|200' | tail -6", timeout=30)
print(stdout.read().decode("utf-8", "ignore").strip())
ssh2.close()
