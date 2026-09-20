# -*- coding: utf-8 -*-
"""探查 .60：设备可达性、SNMP 工具、MappingSync targets 格式"""
import os
import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) 8 台设备 IP 可达性 ==")
print(run("for ip in 192.168.1.1 192.168.1.2 192.168.1.3 192.168.1.10 192.168.1.20 192.168.1.21 192.168.1.30 192.168.1.40; do ping -c1 -W1 $ip >/dev/null 2>&1 && echo \"$ip UP\" || echo \"$ip DOWN\"; done"))

print("\n== 2) SNMP 工具 / snmp_exporter ==")
print(run("which snmpwalk snmpget 2>/dev/null; find / -name 'snmp_exporter' -o -name 'snmp.yml' 2>/dev/null | grep -v proc | head -5; echo '(查找完成)'"))

print("\n== 3) nams-agent MappingSync 生成的 targets 文件 ==")
print(run("cat /opt/nams-gateway/conf/targets/snmp.json 2>/dev/null || echo '(无 targets 文件)'"))

print("\n== 4) mapping.json 内容 ==")
print(run("head -c 1200 /opt/nams-gateway/conf/mapping.json"))

print("\n== 5) 可用端口（探针暴露用） ==")
print(run("ss -tlnp | awk '{print $4}' | grep -oE ':[0-9]+$' | sort -u | tr '\\n' ' '"))

print("\n== 6) .60 外网连通（下载 alertmanager 用） ==")
print(run("curl -s -o /dev/null -w '%{http_code}' --max-time 8 https://mirrors.tuna.tsinghua.edu.cn/ 2>/dev/null || echo FAIL"))

ssh.close()
