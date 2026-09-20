# -*- coding: utf-8 -*-
"""全链路实测：改探针状态触发告警恢复→再触发离线，验证 Alertmanager→nams-agent→云端"""
import paramiko
import os
import json
import time

ssh60 = paramiko.SSHClient()
ssh60.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh60.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)

def run60(cmd, timeout=30):
    stdin, stdout, stderr = ssh60.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

def set_cam02(up, line=False):
    """改 CAM-02 状态并热加载探针（探针每次请求读文件，无需重启）"""
    st = run60("cat /opt/nams-gateway/data/device_status.json")
    data = json.loads(st)
    for d in data["devices"]:
        if d["deviceCode"] == "DEV-CAM-02":
            d["up"] = up
            if line:
                d["lineAbnormal"] = line
    sftp = ssh60.open_sftp()
    with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2))
    sftp.close()
    print("CAM-02 up=%s 已更新" % up)

print("== 1) CAM-02 恢复在线 ==")
set_cam02(True)
print("等待 30s 让 Prometheus 采集 + 规则评估 + Alertmanager 发送 resolved...")
time.sleep(35)

print("\n== 2) Prometheus 告警状态 ==")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|device_code|state|severity' | head -12"))

print("\n== 3) nams-agent 最近告警日志 ==")
print(run60("journalctl -u nams-agent --since '40 seconds ago' --no-pager | grep -iE 'alert|转投|成功|失败' | tail -6"))

print("\n== 4) 缓存目录 ==")
print(run60("ls -la /opt/nams-gateway/cache/ | tail -4"))

print("\n== 5) CAM-02 再次离线 ==")
set_cam02(False)
print("等待 35s 触发离线告警...")
time.sleep(40)

print("\n== 6) Prometheus 告警状态 ==")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|device_code|state|severity' | head -12"))

print("\n== 7) nams-agent 最近告警日志 ==")
print(run60("journalctl -u nams-agent --since '45 seconds ago' --no-pager | grep -iE 'alert|转投|成功|失败' | tail -8"))

ssh60.close()
print("\n== 触发完成，查云端事件见下一步 ==")
