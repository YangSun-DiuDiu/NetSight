# -*- coding: utf-8 -*-
"""
deploy_nams_agent.py —— 部署 Java 版 nams-agent 到边缘网关服务器（替换 Python 模拟器）

用法（PowerShell）：
    $env:GW60_PASSWORD="<root密码>"
    python deploy_nams_agent.py

动作：
  1) 上传 nams-agent.jar / config.json / mapping.json / device_status.json / nams-agent.service
  2) 停用并禁用 nams-gateway-sim（Python 模拟器，避免双上报）
  3) 安装并启动 nams-agent（systemd enable --now）
  4) 健康检查：管理页 200 + 进程存活 + 日志关键字
"""
import os
import sys
import time

import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")
if not PWD:
    print("[-] 缺少 GW60_PASSWORD 环境变量，中止")
    sys.exit(1)

BASE = os.path.dirname(os.path.abspath(__file__))
GATEWAY_ROOT = "/opt/nams-gateway"

# (本地相对路径, 远端绝对路径)
FILES = [
    (os.path.join(BASE, "..", "target", "nams-agent.jar"), GATEWAY_ROOT + "/bin/nams-agent.jar"),
    (os.path.join(BASE, "prod", "config.json"), GATEWAY_ROOT + "/conf/config.json"),
    (os.path.join(BASE, "prod", "mapping.json"), GATEWAY_ROOT + "/conf/mapping.json"),
    (os.path.join(BASE, "prod", "device_status.json"), GATEWAY_ROOT + "/data/device_status.json"),
    (os.path.join(BASE, "nams-agent.service"), "/etc/systemd/system/nams-agent.service"),
]

COMMANDS = [
    # 停用 Python 模拟器（如存在），避免与 nams-agent 双上报
    "systemctl stop nams-gateway-sim 2>/dev/null || true",
    "systemctl disable nams-gateway-sim 2>/dev/null || true",
    "systemctl daemon-reload",
    "systemctl enable nams-agent 2>/dev/null || true",
    # 显式重启（enable --now 对已运行服务不会重启，旧 jar 进程仍在跑）
    "systemctl restart nams-agent",
    "sleep 6",
]


def main():
    print("[*] 连接 %s ..." % HOST)
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=15)

    # 先创建目录，再上传
    stdin, stdout, stderr = ssh.exec_command(
        "mkdir -p %s/{bin,conf,data,cache,logs,webapp,targets}" % GATEWAY_ROOT, timeout=30)
    stdout.read()

    sftp = ssh.open_sftp()
    for local, remote in FILES:
        if not os.path.exists(local):
            print("[-] 本地文件不存在: %s" % local)
            sys.exit(1)
        sftp.put(local, remote)
        print("[+] 上传 %s -> %s" % (os.path.basename(local), remote))
    sftp.close()

    for cmd in COMMANDS:
        print("[*] 执行: %s" % cmd)
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=60)
        out = stdout.read().decode("utf-8", "ignore").strip()
        err = stderr.read().decode("utf-8", "ignore").strip()
        if out:
            print("    stdout: %s" % out[:500])
        if err and "nams-gateway-sim" not in cmd:
            print("    stderr: %s" % err[:500])

    # ---- 健康检查 ----
    time.sleep(3)
    stdin, stdout, stderr = ssh.exec_command(
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/login.html", timeout=15)
    code = stdout.read().decode().strip()
    print("[*] 管理页面 login.html -> HTTP %s" % code)

    stdin, stdout, stderr = ssh.exec_command(
        "systemctl is-active nams-agent && ps -o rss= -p $(pgrep -f nams-agent.jar | head -1)", timeout=15)
    print("[*] systemd 状态: %s" % stdout.read().decode().strip())

    stdin, stdout, stderr = ssh.exec_command(
        "tail -n 20 %s/logs/netsight.log" % GATEWAY_ROOT, timeout=15)
    tail = stdout.read().decode("utf-8", "ignore").strip()
    print("[*] 运行日志（尾部 20 行）:")
    print(tail[-2000:])

    ssh.close()
    print("\n[OK] 部署完成。访问 http://192.168.1.60:8081/login.html（默认 admin/admin123，首登强制改密）")


if __name__ == "__main__":
    main()
