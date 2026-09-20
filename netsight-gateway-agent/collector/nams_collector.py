#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
nams_collector.py — NetSight 自研设备采集探针（国产化采集软件路径，替代 categraf/snmp_exporter）

职责：
  1. 读取采集清单 mapping.json（设备编码/名称/IP/类型/位置/品牌/型号/租户/网关）
  2. 读取设备状态源 device_status.json（热可改；未来接真实 SNMP 时由本探针直接采集设备 OID 写入内存状态）
  3. 暴露 Prometheus 指标 /metrics：
       device_up{...}             1=在线 0=离线
       device_line_abnormal{...}  1=链路异常 0=正常
     标签完整（device_code/device_name/device_ip/device_type/device_location/
            gateway_code/tenant_id/brand/model），供 nams-agent 快照查询与告警模板渲染
  4. 纯标准库实现，无第三方依赖；端口 9258（Prometheus scrape 目标）

部署：/opt/nams-gateway/collector/nams_collector.py（systemd: nams-collector）
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAPPING_FILE = "/opt/nams-gateway/conf/mapping.json"
STATUS_FILE = "/opt/nams-gateway/data/device_status.json"
PORT = 9258

# 设备标签字段顺序（保证输出稳定）
LABEL_KEYS = ("device_code", "device_name", "device_ip", "device_type",
              "device_location", "gateway_code", "tenant_id", "brand", "model")


def load_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def read_status_map():
    """状态源：{deviceCode: {up, lineAbnormal}}；缺失设备默认在线"""
    st = load_json(STATUS_FILE, {})
    return {d.get("deviceCode"): d for d in st.get("devices", []) if d.get("deviceCode")}


def escape_label(value):
    return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


class MetricsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/metrics":
            self.send_response(404)
            self.end_headers()
            return
        mapping = load_json(MAPPING_FILE, {})
        devices = mapping.get("devices", [])
        status_map = read_status_map()
        gateway_code = escape_label(mapping.get("gateway_code", ""))

        lines = [
            "# HELP device_up 设备在线状态 1=在线 0=离线",
            "# TYPE device_up gauge",
            "# HELP device_line_abnormal 设备外线链路异常 1=异常 0=正常",
            "# TYPE device_line_abnormal gauge",
        ]
        for d in devices:
            code = d.get("deviceCode", "")
            st = status_map.get(code, {})
            up = 1 if st.get("up", True) else 0
            line = 1 if st.get("lineAbnormal", False) else 0
            labels = d.get("labels") or {}
            vals = (
                escape_label(code),
                escape_label(d.get("deviceName", "")),
                escape_label(d.get("deviceIp", "")),
                escape_label(d.get("deviceType", "")),
                escape_label(d.get("location", "")),
                gateway_code,
                escape_label(labels.get("tenant_id", "1")),
                escape_label(labels.get("brand", "")),
                escape_label(labels.get("model", "")),
            )
            label_str = ",".join('%s="%s"' % (k, v) for k, v in zip(LABEL_KEYS, vals))
            lines.append("device_up{%s} %d" % (label_str, up))
            lines.append("device_line_abnormal{%s} %d" % (label_str, line))

        body = ("\n".join(lines) + "\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("nams-collector 启动: 监听 :%d, mapping=%s" % (PORT, MAPPING_FILE))
    ThreadingHTTPServer(("0.0.0.0", PORT), MetricsHandler).serve_forever()
