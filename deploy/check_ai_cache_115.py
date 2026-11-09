# -*- coding: utf-8 -*-
"""验证 AI 问诊 Redis 缓存：同一问题连续两次，第二次 fromCache=true。"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

import paramiko

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
BASE = "http://115.159.194.149/prod-api"
PHONE = "15657477316"
MOCK_CODE = "123456"


def http_json(url, method="GET", payload=None, token=None, timeout=45):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}


def main():
    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)
    try:
        _, sj = http_json(BASE + "/auth/sms-code?" + urllib.parse.urlencode({"phone": PHONE}), method="POST", timeout=15)
        print("sms:", sj.get("code"), sj.get("msg"))
        _, lj = http_json(BASE + "/auth/login", method="POST",
                          payload={"phone": PHONE, "code": MOCK_CODE, "clientType": "m"}, timeout=20)
        token = (lj.get("data") or {}).get("token")
        print("login:", lj.get("code"), bool(token))
        if not token:
            return 1
        q = "缓存验证%d 摄像头离线 指示灯不亮 网络不通" % int(time.time())
        body = {"question": q, "orderId": 0, "deviceName": "测试摄像头", "deviceType": "camera",
                "deviceLocation": "测试机房", "faultType": "device_offline",
                "description": "摄像头离线，指示灯不亮，网络不通"}
        for n in (1, 2):
            _, r = http_json(BASE + "/ai/knowledge/ask", method="POST", payload=body, token=token, timeout=45)
            d = r.get("data") or {}
            print("call%d code=%s aiUsed=%s fromCache=%s noHit=%s sources=%s" % (
                n, r.get("code"), d.get("aiUsed"), d.get("fromCache"), d.get("noHit"), len(d.get("sources") or [])))
        return 0
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
