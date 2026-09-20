# -*- coding: utf-8 -*-
"""部署 .60 真实生产环境：
1) 自研采集探针 nams_collector.py（替代 categraf，暴露 device_up/device_line_abnormal）
2) Alertmanager 0.28.1（.60 本地已下载 /opt/alertmanager.tar.gz，webhook -> nams-agent :18080）
3) Prometheus 配置（scrape 探针 + 告警规则 + alerting -> 9093）
4) nams-agent config.json 切 prometheus 源 + 真实租户 webhook token
5) 重启服务并验证
"""
import os
import json
import time
import paramiko

HOST = os.environ.get("GW60_HOST", "192.168.1.60")
USER = os.environ.get("GW60_USER", "root")
PWD = os.environ.get("GW60_PASSWORD", "")
BASE = os.path.dirname(os.path.abspath(__file__))

WEBHOOK_TOKEN = os.environ.get("WEBHOOK_TOKEN", "63c50a35b31943b497355daa5b988f2b")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=20)


def run(cmd, timeout=60, check=False):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "ignore").strip()
    err = stderr.read().decode("utf-8", "ignore").strip()
    code = stdout.channel.recv_exit_status()
    if check and code != 0:
        raise RuntimeError("CMD[%s] => %d\n%s\n%s" % (cmd, code, out, err))
    return out


def sftp_put(local, remote):
    sftp = ssh.open_sftp()
    sftp.put(local, remote)
    sftp.close()
    print("上传 %s -> %s" % (os.path.basename(local), remote))


print("== 1) 自研采集探针 ==")
run("mkdir -p /opt/nams-gateway/collector /opt/alertmanager /etc/prometheus/rules")
sftp_put(os.path.join(BASE, "..", "collector", "nams_collector.py"), "/opt/nams-gateway/collector/nams_collector.py")
sftp_put(os.path.join(BASE, "nams-collector.service"), "/etc/systemd/system/nams-collector.service")
run("systemctl daemon-reload && systemctl enable nams-collector && systemctl restart nams-collector")
time.sleep(2)
print(run("systemctl is-active nams-collector; curl -s --max-time 5 http://127.0.0.1:9258/metrics | head -6"))

print("\n== 2) Alertmanager 安装 ==")
run("tar -xzf /opt/alertmanager.tar.gz -C /opt/alertmanager --strip-components=1 && chmod +x /opt/alertmanager/alertmanager", check=True)
print(run("/opt/alertmanager/alertmanager --version 2>&1 | head -2"))
sftp_put(os.path.join(BASE, "alertmanager.yml"), "/opt/alertmanager/alertmanager.yml")
sftp_put(os.path.join(BASE, "alertmanager.service"), "/etc/systemd/system/alertmanager.service")
run("systemctl daemon-reload && systemctl enable alertmanager && systemctl restart alertmanager")
time.sleep(2)
print(run("systemctl is-active alertmanager; ss -tlnp | grep 9093 || echo '9093 未监听'"))

print("\n== 3) Prometheus 配置 ==")
run("cp -a /etc/prometheus/prometheus.yml /etc/prometheus/prometheus.yml.bak-$(date +%Y%m%d%H%M%S)")
sftp_put(os.path.join(BASE, "prometheus.yml"), "/etc/prometheus/prometheus.yml")
sftp_put(os.path.join(BASE, "nams_device_rules.yml"), "/etc/prometheus/rules/nams_device_rules.yml")
print(run("promtool check config /etc/prometheus/prometheus.yml 2>&1 | tail -3"))
run("systemctl reload prometheus || systemctl restart prometheus")
time.sleep(3)
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=up' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']['result']; [print(x['metric'].get('job'), x['metric'].get('instance'), x['value'][1]) for x in d]\""))
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_up' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']['result']; [print(x['metric'].get('device_code'), x['metric'].get('device_ip'), x['value'][1]) for x in d]\""))
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/query?query=device_line_abnormal' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']['result']; [print(x['metric'].get('device_code'), x['value'][1]) for x in d]\""))
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/alerts' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']['alerts']; print('告警数:', len(d)); [print(a['labels'].get('alertname'), a['labels'].get('device_code'), a['state'], a['labels'].get('severity')) for a in d]\""))
print(run("curl -s --max-time 5 'http://127.0.0.1:9090/api/v1/targets' | python3 -c \"import sys,json; d=json.load(sys.stdin)['data']['activeTargets']; [print(t['labels'].get('job'), t['scrapeUrl'], 'UP' if t['health']=='up' else t['health']) for t in d]\""))

print("\n== 4) nams-agent config.json 切真实源 ==")
cfg_path = "/opt/nams-gateway/conf/config.json"
cfg = json.loads(run("cat %s" % cfg_path))
cfg["prometheus"]["source"] = "prometheus"
cfg["cloud"]["alert_webhook_token"] = WEBHOOK_TOKEN
run("cp -a %s %s.bak-%s" % (cfg_path, cfg_path, time.strftime("%Y%m%d%H%M%S")))
sftp = ssh.open_sftp()
with sftp.open(cfg_path, "w") as f:
    f.write(json.dumps(cfg, ensure_ascii=False, indent=2))
sftp.close()
print(run("cat %s | python3 -c \"import sys,json; c=json.load(sys.stdin); print('source:', c['prometheus']['source']); print('token:', c['cloud']['alert_webhook_token'][:8]+'...')\"" % cfg_path))
run("systemctl restart nams-agent")
time.sleep(4)
print(run("systemctl is-active nams-agent; journalctl -u nams-agent -n 8 --no-pager | tail -8"))

ssh.close()
print("\n== 部署完成 ==")
