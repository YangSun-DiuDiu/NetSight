#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NetSight 自研设备采集探针（nams-collector）v1.0
国产化采集路径（替代 categraf）：指标不出内网，仅告警上云。

数据流：云端 mapping 下发 → 网关生成 conf/targets/snmp.json（file_sd）
      → 本探针读取 targets，周期 ICMP 探测设备在线/链路质量
      → 暴露 Prometheus 文本指标（:9258/metrics）
      → Prometheus 抓取 + 告警规则 → Alertmanager → nams-agent(:18080) → 云端

指标契约（与 nams-agent SnapshotCollector source=prometheus 一致）：
  device_up{device_code=..., device_name=..., device_ip=..., device_type=...,
            device_location=..., tenant_id=..., gateway_code=...} 1|0
  device_line_abnormal{...} 1|0

外线异常判定：ICMP 平均 RTT > 150ms 视为链路质量差（可配置阈值）。
"""
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TARGETS_FILE = "/opt/nams-gateway/conf/targets/snmp.json"
METRICS_PORT = 9258
PROBE_INTERVAL = 15
RTT_THRESHOLD_MS = 150.0

_lock = threading.Lock()
_metrics = {}  # device_code -> (up, line, labels_dict, ip)


def probe_devices():
    """读取 targets 并探测全部设备状态"""
    global _metrics
    try:
        with open(TARGETS_FILE, "r", encoding="utf-8") as f:
            targets = json.load(f)
    except Exception as e:
        print("[collector] 读取 targets 失败: %s" % e, flush=True)
        return
    result = {}
    for t in targets:
        labels = dict(t.get("labels", {}) or {})
        code = labels.get("device_code", "")
        tgt = (t.get("targets") or [""])[0]
        ip = tgt.split(":")[0] if tgt else ""
        if not code or not ip:
            continue
        up, line = 0, 0
        try:
            # ICMP 探测：-c 2 -W 1（2 个包、单包超时 1s）
            r = subprocess.run(["ping", "-c", "2", "-W", "1", ip],
                               capture_output=True, timeout=4)
            up = 1 if r.returncode == 0 else 0
            if up == 1:
                # 解析平均 RTT：rtt min/avg/max/mdev = 0.123/0.456/0.789/0.012 ms
                out = r.stdout.decode("utf-8", "ignore")
                m = re.search(r"min/avg/max/mdev\s*=\s*[\d.]+/([\d.]+)/", out)
                if m and float(m.group(1)) > RTT_THRESHOLD_MS:
                    line = 1
        except Exception:
            up, line = 0, 0
        result[code] = (up, line, labels, ip)
    with _lock:
        _metrics = result
    print("[collector] 探测完成: %d 台（%d 在线）" % (len(result),
          sum(1 for v in result.values() if v[0] == 1)), flush=True)


def render_metrics():
    """渲染 Prometheus 文本格式指标"""
    with _lock:
        snap = dict(_metrics)
    lines = []
    lines.append("# HELP device_up 设备在线状态（自研采集探针探测）")
    lines.append("# TYPE device_up gauge")
    lines.append("# HELP device_line_abnormal 设备外线/链路质量异常")
    lines.append("# TYPE device_line_abnormal gauge")
    for code, (up, line, labels, ip) in sorted(snap.items()):
        lab = ['device_code="%s"' % code]
        for k in ("device_name", "device_ip", "device_type", "device_location",
                  "tenant_id", "gateway_code"):
            v = labels.get(k, "")
            if k == "device_ip" and not v:
                v = ip
            lab.append('%s="%s"' % (k, v.replace('"', "\\\"")))
        label_str = ",".join(lab)
        lines.append("device_up{%s} %d" % (label_str, up))
        lines.append("device_line_abnormal{%s} %d" % (label_str, line))
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            body = render_metrics().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


def main():
    # 首次立即探测 + 周期刷新
    probe_devices()
    t = threading.Thread(target=lambda: (time.sleep(PROBE_INTERVAL), probe_devices()),
                         daemon=True)
    # 周期线程（每 15s）
    def loop():
        while True:
            time.sleep(PROBE_INTERVAL)
            probe_devices()
    threading.Thread(target=loop, daemon=True).start()
    print("[collector] nams-collector 启动: :%d/metrics（%d 秒探测周期）"
          % (METRICS_PORT, PROBE_INTERVAL), flush=True)
    ThreadingHTTPServer(("0.0.0.0", METRICS_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
