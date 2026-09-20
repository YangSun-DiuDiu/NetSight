# -*- coding: utf-8 -*-
"""NetSight 全量云端部署（paramiko）：
1) 上传最新 jar → 备份旧 jar → 重启 systemd netsight-server
2) 上传前端 dist → /opt/nams-ui
3) 安装/配置 Nginx（80 端口，/prod-api 反代 8080，/ws/ 反代 WebSocket）
4) 健康检查 + 页面/接口/WS 验证
目标：192.168.1.55
"""
import paramiko
import time
import os

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"
JAR = r"E:\gitee\NetSight1.0\netsight-server\target\netsight-server.jar"
DIST_TGZ = r"E:\gitee\NetSight1.0\netsight-ui\dist.tar.gz"
APP_DIR = "/opt/nams-server"
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

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location /edge/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # 告警 webhook 接入（Alertmanager/网关推送 /alert/push，须与 /edge/ 同时保留，勿删）
    location /alert/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
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

    # ---------- 1. 后端 jar ----------
    run("mkdir -p %s/logs" % APP_DIR)
    sftp = cli.open_sftp()
    sftp.put(JAR, APP_DIR + "/netsight-server.jar.new")
    sftp.close()
    print("==> 新 jar 上传完成 (%d KB)" % (os.path.getsize(JAR) // 1024))
    run("cd %s && mv netsight-server.jar netsight-server.jar.bak.$(date +%%Y%%m%%d%%H%%M%%S) 2>/dev/null; mv netsight-server.jar.new netsight-server.jar" % APP_DIR)
    run("systemctl restart netsight-server")
    print("==> 后端已重启，等待健康检查...")

    ok = False
    for i in range(25):
        time.sleep(3)
        code = run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000")
        print("    第 %d 次探测: HTTP %s" % (i + 1, code))
        if code == "200":
            ok = True
            break
    if not ok:
        print("==> 后端启动超时！日志尾部：")
        print(run("tail -30 %s/logs/stderr.log" % APP_DIR))
        cli.close()
        raise SystemExit(1)
    print("==> 后端健康 UP")

    # ---------- 2. 前端 dist ----------
    if not os.path.exists(DIST_TGZ):
        print("!! dist.tar.gz 不存在，跳过前端部署")
    else:
        sftp = cli.open_sftp()
        sftp.put(DIST_TGZ, "/tmp/nams-dist.tar.gz")
        sftp.close()
        run("rm -rf %s && mkdir -p %s" % (UI_DIR, UI_DIR))
        run("tar -xzf /tmp/nams-dist.tar.gz -C %s" % UI_DIR)
        run("chown -R root:root %s" % UI_DIR)
        print("==> 前端 dist 部署完成: %s" % run("ls %s/dist | head -5" % UI_DIR).replace("\n", ", "))

    # ---------- 3. Nginx ----------
    nginx_v = run("which nginx && nginx -v 2>&1 || echo NOT_INSTALLED")
    if "NOT_INSTALLED" in nginx_v:
        print("==> 安装 Nginx...")
        run("apt-get update -qq && apt-get install -y -qq nginx", timeout=300)
        nginx_v = run("nginx -v 2>&1 || echo FAIL")
        print("   ", nginx_v)
    sftp = cli.open_sftp()
    with sftp.open("/etc/nginx/conf.d/nams.conf", "w") as f:
        f.write(NGINX_CONF)
    sftp.close()
    t = run("nginx -t 2>&1")
    print("==> nginx -t:", t.replace("\n", " | "))
    if "successful" in t:
        run("systemctl enable nginx && systemctl restart nginx")
        print("==> Nginx 已启动")
    else:
        print("!! Nginx 配置检查失败，跳过启动")
        cli.close()
        raise SystemExit(1)

    # ---------- 4. 验证 ----------
    time.sleep(2)
    print("==> 验证:")
    print("  首页 HTTP:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/"))
    print("  首页含NetSight:", "NetSight" in run("curl -s http://127.0.0.1/ | head -20"))
    print("  /prod-api 代理:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/prod-api/actuator/health"))
    ws = run("curl -s -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13' http://127.0.0.1/ws/push")
    print("  /ws/push 握手:", ws, "(101=成功)")
    print("==> 部署完成！访问 http://%s （需云安全组放行 80 端口）" % HOST)

finally:
    cli.close()
