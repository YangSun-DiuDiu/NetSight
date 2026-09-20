# -*- coding: utf-8 -*-
"""上传本机 temurin21-jre.tar.gz 到 .60 并安装到 /opt/java21"""
import os
import sys
import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")

LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "temurin21-jre.tar.gz")
if not os.path.exists(LOCAL):
    print("[-] 本地 JRE 包不存在: %s" % LOCAL)
    sys.exit(1)

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

def run(cmd, timeout=300):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip(), stderr.read().decode("utf-8", "ignore").strip()

print("[*] 上传 JRE 包（52MB）...")
sftp = ssh.open_sftp()
sftp.put(LOCAL, "/tmp/temurin21-jre.tar.gz")
sftp.close()
print("[+] 上传完成")

out, err = run("mkdir -p /opt/java21 && tar xzf /tmp/temurin21-jre.tar.gz -C /opt/java21 --strip-components=1 && rm -f /tmp/temurin21-jre.tar.gz")
print("[*] 解压: %s %s" % (out, err))

out, err = run("/opt/java21/bin/java -version 2>&1", 30)
print("[*] java -version:")
print(out)

ssh.close()
