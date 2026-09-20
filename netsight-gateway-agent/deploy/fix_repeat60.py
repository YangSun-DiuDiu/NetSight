# -*- coding: utf-8 -*-
"""调整 Alertmanager repeat_interval=5m + 验证恢复/再触发通知"""
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

print("== 1) 改 repeat_interval: 4h -> 5m ==")
conf = run60("cat /opt/alertmanager/alertmanager.yml")
conf = conf.replace("repeat_interval: 4h", "repeat_interval: 5m")
sftp = ssh60.open_sftp()
with sftp.open("/opt/alertmanager/alertmanager.yml", "w") as f:
    f.write(conf)
sftp.close()
print(run60("grep repeat_interval /opt/alertmanager/alertmanager.yml"))
print(run60("kill -HUP $(pgrep -f 'alertmanager --config') && echo HUP-ok"))
time.sleep(2)
print(run60("systemctl is-active alertmanager"))

def set_cam02(up):
    st = run60("cat /opt/nams-gateway/data/device_status.json")
    data = json.loads(st)
    for d in data["devices"]:
        if d["deviceCode"] == "DEV-CAM-02":
            d["up"] = up
    sftp = ssh60.open_sftp()
    with sftp.open("/opt/nams-gateway/data/device_status.json", "w") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2))
    sftp.close()
    print("CAM-02 up=%s 已更新" % up)

print("\n== 2) CAM-02 恢复（触发 resolved 通知） ==")
set_cam02(True)
time.sleep(40)
print("Prometheus 告警：")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|state' | head -8"))
print("nams-agent 最近告警日志：")
print(run60("journalctl -u nams-agent --since '45 seconds ago' --no-pager | grep -iE 'alert|转投' | tail -4"))

print("\n== 3) CAM-02 再次离线（距上次 5m 内，验证 repeat=5m 后行为） ==")
set_cam02(False)
time.sleep(40)
print("Prometheus 告警：")
print(run60("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -m json.tool | grep -E 'alertname|state' | head -8"))
print("nams-agent 最近告警日志：")
print(run60("journalctl -u nams-agent --since '45 seconds ago' --no-pager | grep -iE 'alert|转投' | tail -6"))

ssh60.close()
print("\n== 完成，查云端事件 ==")
