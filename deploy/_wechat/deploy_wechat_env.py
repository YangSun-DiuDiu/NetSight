# -*- coding: utf-8 -*-
"""
微信 JS-SDK 测试环境一键部署脚本（测试服务器 115.159.194.149）
=============================================================
1. 上传 /opt/wechat-test/{index.html, sign_server.py}
2. 写 /opt/wechat-test/wechat.env（凭据走环境变量 WX_APPID/WX_SECRET 传入，不落盘到本机）
3. 新增 Nginx server 块：api.netsightplus.cn（80 校验文件 + 443 SSL + /wx-sign/ 代理 9091）
4. 启动签名服务（nohup + pid 文件）
5. nginx -t && reload，公网验证

用法（PowerShell）:
    $env:WX_APPID='wx...'; $env:WX_SECRET='...'
    python deploy_wechat_env.py [--cert-pem 路径 --cert-key 路径]
"""
import argparse
import base64
import json
import os
import sys
import time

import paramiko

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_cfg_candidates = [
    os.path.join(SCRIPT_DIR, "..", "deploy.config.json"),
    r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json",
]
_cfg_path = next((p for p in _cfg_candidates if os.path.isfile(p)), None)
if not _cfg_path:
    raise SystemExit("deploy.config.json not found")
with open(_cfg_path, encoding="utf-8") as f:
    cfg = json.load(f)

APPID = os.environ.get("WX_APPID", "")
SECRET = os.environ.get("WX_SECRET", "")

REMOTE_DIR = "/opt/wechat-test"
SIGN_PORT = 9091
LOCAL_INDEX = os.path.join(SCRIPT_DIR, "index.html")
LOCAL_SIGN = os.path.join(SCRIPT_DIR, "sign_server.py")

NGINX_CONF = r"""# 微信公众号 JS-SDK 测试环境 - api.netsightplus.cn
# 注意：本文件由 deploy_wechat_env.py 生成/覆盖，勿手工编辑
server {
    listen 80;
    server_name api.netsightplus.cn;

    # 微信 JS 接口安全域名校验文件（也保留在 nams-ui/dist 下，双保险）
    location = /MP_verify_V7Tu44jhDZN8jdyl.txt {
        root /opt/nams-ui/dist;
    }

    # http 访问强制跳转 https
    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name api.netsightplus.cn;

    ssl_certificate     /etc/nginx/ssl/api.netsightplus.cn.pem;
    ssl_certificate_key /etc/nginx/ssl/api.netsightplus.cn.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    root /opt/wechat-test;
    index index.html;

    # 微信校验文件（精确匹配优先于 / 的 try_files 回退）
    location = /MP_verify_V7Tu44jhDZN8jdyl.txt {
        root /opt/nams-ui/dist;
    }

    # 微信 JS-SDK 签名接口（转发到本机 9091 签名服务）
    location /wx-sign/ {
        proxy_pass http://127.0.0.1:9091/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location = /index.html { add_header Cache-Control "no-cache, must-revalidate"; }
    location / { try_files $uri $uri/ /index.html; }
}
"""


def run(ssh, cmd, timeout=60):
    _, out, err = ssh.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace").strip()
    e = err.read().decode("utf-8", "replace").strip()
    return o, e


def sftp_put(ssh, local, remote):
    sftp = ssh.open_sftp()
    try:
        sftp.put(local, remote)
    finally:
        sftp.close()
    print(f"上传 {local} -> {remote}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cert-pem", help="本地证书 pem 文件路径（可选，指定后自动上传到 /etc/nginx/ssl/）")
    ap.add_argument("--cert-key", help="本地证书 key 文件路径（可选）")
    ap.add_argument("--no-nginx", action="store_true", help="只部署文件与签名服务，不改 Nginx")
    args = ap.parse_args()

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(hostname=cfg["host"], port=int(cfg.get("port", 22)),
                username=cfg["user"], password=cfg["password"], timeout=30)

    # 1. 目录 + 上传文件
    run(ssh, f"mkdir -p {REMOTE_DIR} /etc/nginx/ssl")
    sftp_put(ssh, LOCAL_INDEX, f"{REMOTE_DIR}/index.html")
    sftp_put(ssh, LOCAL_SIGN, f"{REMOTE_DIR}/sign_server.py")

    # 2. 凭据写入远端受保护文件（本机不落盘）
    if APPID and SECRET:
        env_content = f"WX_APPID={APPID}\nWX_SECRET={SECRET}\n"
        b64 = base64.b64encode(env_content.encode("utf-8")).decode("ascii")
        run(ssh, f"echo {b64} | base64 -d > {REMOTE_DIR}/wechat.env && chmod 600 {REMOTE_DIR}/wechat.env")
        print("wechat.env 已写入（chmod 600）")
    else:
        print("警告: 未提供 WX_APPID/WX_SECRET，wechat.env 未写入，签名接口将不可用")

    # 3. 证书上传
    if args.cert_pem and args.cert_key:
        sftp_put(ssh, args.cert_pem, "/etc/nginx/ssl/api.netsightplus.cn.pem")
        sftp_put(ssh, args.cert_key, "/etc/nginx/ssl/api.netsightplus.cn.key")
        run(ssh, "chmod 600 /etc/nginx/ssl/api.netsightplus.cn.key")
        print("证书已上传到 /etc/nginx/ssl/")
    else:
        print("提示: 未指定证书文件，Nginx 443 配置将保留但暂时不可用（或 --no-nginx 跳过）")

    # 4. 启动/重启签名服务（nohup + pid，与 nams-server 同风格）
    run(ssh, f"if [ -f {REMOTE_DIR}/sign.pid ]; then kill $(cat {REMOTE_DIR}/sign.pid) 2>/dev/null; fi; sleep 1")
    o, e = run(ssh, f"cd {REMOTE_DIR} && nohup python3 sign_server.py < /dev/null > sign.log 2>&1 & echo $! > sign.pid")
    time.sleep(2)
    o, e = run(ssh, f"cat {REMOTE_DIR}/sign.pid; echo; ss -tlnp | grep {SIGN_PORT} || echo 'SIGN NOT LISTENING'")
    print("签名服务 PID/端口:", o or e)

    # 5. Nginx 配置
    if not args.no_nginx:
        conf = NGINX_CONF
        b64 = base64.b64encode(conf.encode("utf-8")).decode("ascii")
        run(ssh, f"echo {b64} | base64 -d > /etc/nginx/sites-available/wechat-test.conf")
        run(ssh, "ln -sf /etc/nginx/sites-available/wechat-test.conf /etc/nginx/sites-enabled/wechat-test.conf")
        o, e = run(ssh, "nginx -t 2>&1; echo EXIT=$?")
        print("nginx -t:", o or e)
        if "EXIT=0" in (o or e) and "successful" in (o or e):
            run(ssh, "nginx -s reload")
            print("nginx 已 reload")
        else:
            print("错误: nginx -t 未通过，请检查后手动 reload；已生成 /etc/nginx/sites-available/wechat-test.conf")
    else:
        print("已跳过 Nginx 配置（--no-nginx）")

    ssh.close()
    print("=== deploy done ===")


if __name__ == "__main__":
    main()
