# -*- coding: utf-8 -*-
"""探测 .60 服务器 Java 环境"""
import os
import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

for cmd in [
    "which java && java -version 2>&1 || echo 'NO_JAVA'",
    "ls /usr/lib/jvm/ 2>/dev/null || echo 'NO_JVM_DIR'",
    "cat /etc/os-release | head -2",
    "df -h /opt | tail -1",
    "free -m | head -2",
]:
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=20)
    print("==> %s" % cmd)
    print(stdout.read().decode("utf-8", "ignore").strip())
    err = stderr.read().decode("utf-8", "ignore").strip()
    if err:
        print("[stderr] %s" % err[:300])

ssh.close()
