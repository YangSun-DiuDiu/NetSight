# -*- coding: utf-8 -*-
"""查看 .60 重启后日志 + 验证 jar 含 probe 代码"""
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

print("== 22:23:17 之后日志（tail -60）==")
print(run("grep -n '2026-09-12 22:2[3-9]' /opt/nams-gateway/logs/netsight.log | tail -60"))

print("\n== grep 探针/查询设备/快照 ==")
print(run("grep -E '探针|查询设备|快照|读取探针' /opt/nams-gateway/logs/netsight.log | tail -20"))

print("\n== jar 内 SnapshotCollector 是否含 probe ==")
print(run("cd /opt/nams-gateway/bin && unzip -o -q nams-agent.jar 'com/netsight/gateway/snapshot/SnapshotCollector.class' -d /tmp/jarcheck && javap -p -c /tmp/jarcheck/com/netsight/gateway/snapshot/SnapshotCollector.class 2>/dev/null | grep -c queryProbeFile || echo 0"))

print("\n== 进程启动时间 ==")
print(run("ps -o pid,lstart,cmd -p $(pgrep -f nams-agent.jar | head -1)"))

ssh.close()
