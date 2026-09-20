#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NetSight 边缘网关模拟器（第 6 周）
部署于内网前置机 192.168.1.60，模拟真实边缘网关：
- 周期上报设备状态（模拟 Prometheus 采集结果，指标不出内网）
- 心跳保活
- 设备状态从 devices.json 读取：修改该文件即可模拟设备离线/恢复/链路异常，无需重启
用法：
  python3 gateway_sim.py config.json
"""
import json
import time
import sys
import signal
import logging
import urllib.request
import urllib.error

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger("gateway-sim")


class GatewaySim:
    def __init__(self, cfg):
        self.cfg = cfg
        self.target = cfg["target_url"].rstrip("/")
        self.token = cfg["gateway_token"]
        self.tenant_id = cfg.get("tenant_id", 1)
        self.ip = cfg.get("ip_address", "")
        self.devices_file = cfg["devices_file"]
        self.running = True

    def _post(self, path, body):
        url = self.target + path
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json; charset=utf-8")
        req.add_header("X-Gateway-Token", self.token)
        req.add_header("X-Netsight-Tenant-Id", str(self.tenant_id))
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                text = resp.read().decode("utf-8", "ignore")
                return resp.status, text
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "ignore")
        except Exception as e:
            return -1, str(e)

    def load_devices(self):
        """读取设备状态文件；失败时返回空列表并告警"""
        try:
            with open(self.devices_file, "r", encoding="utf-8") as f:
                return json.load(f).get("devices", [])
        except Exception as e:
            log.warning("读取设备状态文件失败: %s", e)
            return []

    def report_status(self):
        devices = self.load_devices()
        if not devices:
            log.warning("设备清单为空，跳过上报")
            return
        body = {
            "ipAddress": self.ip,
            "devices": [{"deviceCode": d["deviceCode"],
                         "up": d.get("up", 1),
                         "lineAbnormal": d.get("lineAbnormal", 0)} for d in devices]
        }
        code, text = self._post("/edge/report/status", body)
        log.info("状态上报: HTTP %s, %s 台设备 -> %s", code, len(devices), text[:120])

    def report_heartbeat(self):
        code, text = self._post("/edge/report/heartbeat", {"ipAddress": self.ip})
        log.info("心跳上报: HTTP %s -> %s", code, text[:120])

    def run(self):
        status_interval = self.cfg.get("status_interval", 15)
        heartbeat_interval = self.cfg.get("heartbeat_interval", 30)
        last_hb = 0
        log.info("边缘网关模拟器启动: target=%s token=%s.. 设备文件=%s",
                 self.target, self.token[:8], self.devices_file)
        while self.running:
            try:
                self.report_status()
                now = time.time()
                if now - last_hb >= heartbeat_interval:
                    self.report_heartbeat()
                    last_hb = now
            except Exception as e:
                log.error("上报异常: %s", e)
            time.sleep(status_interval)

    def stop(self, *args):
        log.info("收到停止信号，模拟器退出")
        self.running = False


def main():
    if len(sys.argv) < 2:
        print("用法: python3 gateway_sim.py config.json")
        sys.exit(1)
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        cfg = json.load(f)
    sim = GatewaySim(cfg)
    signal.signal(signal.SIGINT, sim.stop)
    signal.signal(signal.SIGTERM, sim.stop)
    sim.run()


if __name__ == "__main__":
    main()
