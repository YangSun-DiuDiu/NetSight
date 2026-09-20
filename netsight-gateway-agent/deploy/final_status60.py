# -*- coding: utf-8 -*-
"""最终状态确认"""
import paramiko
import os

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.60", username="root", password=os.environ.get("GW60_PASSWORD", "Chinaunicom@1358"), timeout=15)
def run(cmd, timeout=30):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", "ignore").strip()

print("== .60 服务状态 ==")
print(run("for s in prometheus blackbox_exporter alertmanager node_exporter nams-agent nams-collector; do printf '%-24s %s\n' \"$s\" \"$(systemctl is-active $s)\"; done"))

print("\n== 端口监听 ==")
print(run("ss -tlnp | grep -E ':(9090|9093|9100|9115|18080|8081|9258)\\b' | awk '{print $4}' | sort"))

print("\n== Prometheus targets 汇总 ==")
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | python3 -c \"import sys,json; d=json.load(sys.stdin); jobs={}; [jobs.setdefault(t['labels'].get('job'),[0,0]).__setitem__(1, jobs[t['labels'].get('job')][1]+1 if t['health']=='up' else jobs[t['labels'].get('job')][1]) for t in d['data']['activeTargets']]; jobs.setdefault(t['labels'].get('job'),[0,0]) if False else None; [print(j, jobs[j]) for j in sorted(jobs)]\""))

ssh.close()
