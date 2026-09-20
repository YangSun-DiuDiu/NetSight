# -*- coding: utf-8 -*-
"""排查：Prometheus 抓取探针失败 + nams-agent 清单 401"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== 1) Prometheus targets 详细 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | head -c 2000"))

print("\n== 2) 探针 /metrics 直抓（模拟 Prometheus 抓取） ==")
print(run("curl -sv --max-time 5 http://127.0.0.1:9258/metrics 2>&1 | grep -E '^< |^\\* |device_up\\{' | head -12"))

print("\n== 3) nams-agent 日志（清单 401 详情） ==")
print(run("journalctl -u nams-agent -n 40 --no-pager | grep -E '401|清单|mapping|CloudClient|上报|status' | tail -12"))

print("\n== 4) nams-agent 本地概览（快照源状态） ==")
print(run("curl -s --max-time 5 -u admin:'Admin@2026' http://127.0.0.1:8081/local/api/overview 2>/dev/null | head -c 1000 || echo '需登录态'"))

print("\n== 5) 云端 .55 最近设备状态上报 ==")
print(run("tail -20 /opt/nams-server/logs/netsight.log 2>/dev/null | grep -E 'edge|status' | tail -5"))

ssh.close()
