# -*- coding: utf-8 -*-
"""NetSight 前端 dist + Nginx 部署（paramiko，后端已就绪）"""
import paramiko
import time
import os

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"
DIST_TGZ = r"E:\gitee\NetSight1.0\netsight-ui\dist.tar.gz"
UI_DIR = "/opt/nams-ui"

NGINX_CONF = """server {
    listen 80;
    server_name _;
    root /opt/nams-ui/dist;
    index index.html;

    client_max_body_size 20m;

    location /prod-api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /edge/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /alert/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
"""

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())


def run(cmd, timeout=60):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


try:
    cli.connect(HOST, username=USER, password=PWD, timeout=15)
    print("==> 已连接", HOST)

    # 1. 前端 dist
    if os.path.exists(DIST_TGZ):
        sftp = cli.open_sftp()
        sftp.put(DIST_TGZ, "/tmp/nams-dist.tar.gz")
        sftp.close()
        run("rm -rf %s && mkdir -p %s" % (UI_DIR, UI_DIR))
        run("tar -xzf /tmp/nams-dist.tar.gz -C %s" % UI_DIR)
        run("chown -R root:root %s" % UI_DIR)
        print("==> 前端 dist 部署完成: %s" % run("ls %s/dist | head -6" % UI_DIR).replace("\n", ", "))

    # 2. Nginx
    if "NOT_INSTALLED" in run("which nginx || echo NOT_INSTALLED"):
        print("==> 安装 Nginx...")
        print(run("apt-get update -qq && apt-get install -y -qq nginx", timeout=300)[-200:])
        print("   ", run("nginx -v 2>&1"))
    sftp = cli.open_sftp()
    with sftp.open("/etc/nginx/conf.d/nams.conf", "w") as f:
        f.write(NGINX_CONF)
    sftp.close()
    t = run("nginx -t 2>&1")
    print("==> nginx -t:", t.replace("\n", " | "))
    if "successful" in t:
        run("systemctl enable nginx 2>/dev/null; systemctl restart nginx")
        print("==> Nginx 已启动")
    else:
        print("!! Nginx 配置失败"); cli.close(); raise SystemExit(1)

    # 3. 验证
    time.sleep(2)
    print("==> 验证:")
    print("  首页 HTTP:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/"))
    print("  首页含NetSight:", "NetSight" in run("curl -s http://127.0.0.1/ | head -20"))
    print("  页面标题:", run("curl -s http://127.0.0.1/ | grep -o '<title>[^<]*' | head -1"))
    print("  /prod-api 代理:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/prod-api/actuator/health"))
    ws = run("curl -s -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13' http://127.0.0.1/ws/push")
    print("  /ws/push 握手:", ws, "(101=成功)")
    print("==> 部署完成！访问 http://%s （需云安全组放行 80 端口）" % HOST)

finally:
    cli.close()
