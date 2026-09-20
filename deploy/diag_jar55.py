# -*- coding: utf-8 -*-
"""查云端 jar 时间戳与版本"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 云端 jar 信息 ==")
print(run("ls -la /opt/nams-server/netsight-server.jar /opt/nams-server/netsight-server.jar.bak.* 2>/dev/null | tail -5"))
print("\n== jar 内是否有 configMapping 类 ==")
print(run("unzip -l /opt/nams-server/netsight-server.jar | grep -i 'GatewayReportController' | head -3"))
print("\n== 服务启动时间 ==")
print(run("systemctl show netsight-server -p ActiveEnterTimestamp"))

ssh.close()
