# -*- coding: utf-8 -*-
"""修正规则文件注释（blackbox 生成，非自研探针）"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

content = run("cat /etc/prometheus/rules/nams_device_rules.yml")
old = "# device_up / device_line_abnormal 由自研采集探针 nams-collector 暴露，标签完整（device_code 等）"
new = "# device_up / device_line_abnormal 由 blackbox_exporter ICMP probe 经 recording rule 生成（Prometheus 全家桶），标签完整（device_code 等）"
if old in content:
    content = content.replace(old, new)
    sftp = ssh.open_sftp()
    with sftp.open("/etc/prometheus/rules/nams_device_rules.yml", "w") as f:
        f.write(content)
    sftp.close()
    print("注释已修正")
    print(run("/usr/bin/promtool check rules /etc/prometheus/rules/nams_device_rules.yml 2>&1 | tail -2"))
    print(run("kill -HUP $(pgrep -f 'prometheus --config.file' | head -1) && echo 'Prometheus 热重载完成'"))
else:
    print("注释已是最新或未命中，跳过")
ssh.close()
