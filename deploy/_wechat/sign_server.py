# -*- coding: utf-8 -*-
"""
微信 JS-SDK 签名服务（测试环境）
================================
提供 GET /sign?url=<页面完整URL> 返回 wx.config 所需参数：
    {"appId": "...", "timestamp": 123, "nonceStr": "...", "signature": "..."}

- appid/secret 从环境变量 WX_APPID / WX_SECRET 或同目录 wechat.env 读取
- access_token / jsapi_ticket 各缓存 7000 秒
- 纯标准库实现（http.server + urllib），无第三方依赖
- 仅绑定 127.0.0.1，由 Nginx 反向代理暴露
"""
import hashlib
import json
import os
import random
import string
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = int(os.environ.get("WX_SIGN_PORT", "9091"))

APPID = os.environ.get("WX_APPID", "")
SECRET = os.environ.get("WX_SECRET", "")

# 若环境变量未设置，尝试从同目录 wechat.env 读取（KEY=VALUE 格式）
_ENV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wechat.env")
if (not APPID or not SECRET) and os.path.isfile(_ENV_FILE):
    with open(_ENV_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k == "WX_APPID" and not APPID:
                APPID = v.strip()
            elif k == "WX_SECRET" and not SECRET:
                SECRET = v.strip()

_token_cache = {"at": None, "expire": 0, "lock": threading.Lock()}
_ticket_cache = {"tk": None, "expire": 0, "lock": threading.Lock()}


def http_get_json(url, timeout=10):
    req = urllib.request.Request(url, headers={"User-Agent": "netsight-wechat-test/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_access_token():
    now = time.time()
    if _token_cache["at"] and now < _token_cache["expire"]:
        return _token_cache["at"]
    with _token_cache["lock"]:
        if _token_cache["at"] and now < _token_cache["expire"]:
            return _token_cache["at"]
        url = ("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential"
               "&appid={}&secret={}".format(urllib.parse.quote(APPID), urllib.parse.quote(SECRET)))
        data = http_get_json(url)
        if "access_token" not in data:
            raise RuntimeError("获取 access_token 失败: " + json.dumps(data, ensure_ascii=False))
        _token_cache["at"] = data["access_token"]
        _token_cache["expire"] = now + int(data.get("expires_in", 7200)) - 200
        return _token_cache["at"]


def get_jsapi_ticket():
    now = time.time()
    if _ticket_cache["tk"] and now < _ticket_cache["expire"]:
        return _ticket_cache["tk"]
    with _ticket_cache["lock"]:
        if _ticket_cache["tk"] and now < _ticket_cache["expire"]:
            return _ticket_cache["tk"]
        token = get_access_token()
        url = ("https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token={}&type=jsapi"
               .format(urllib.parse.quote(token)))
        data = http_get_json(url)
        if data.get("errcode", 0) != 0 or "ticket" not in data:
            raise RuntimeError("获取 jsapi_ticket 失败: " + json.dumps(data, ensure_ascii=False))
        _ticket_cache["tk"] = data["ticket"]
        _ticket_cache["expire"] = now + int(data.get("expires_in", 7200)) - 200
        return _ticket_cache["tk"]


def make_signature(ticket, noncestr, timestamp, url):
    raw = "jsapi_ticket={}&noncestr={}&timestamp={}&url={}".format(ticket, noncestr, timestamp, url)
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def handle_sign(query):
    if not APPID or not SECRET:
        return {"ok": False, "error": "WX_APPID/WX_SECRET 未配置（检查 wechat.env 或环境变量）"}
    url = (query.get("url") or [""])[0]
    if not url:
        return {"ok": False, "error": "缺少 url 参数（页面完整 URL，不含 # 部分）"}
    url = urllib.parse.unquote(url).split("#")[0]
    if not url.startswith("http"):
        return {"ok": False, "error": "url 必须为 http(s) 完整地址"}
    ticket = get_jsapi_ticket()
    timestamp = str(int(time.time()))
    noncestr = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    signature = make_signature(ticket, noncestr, timestamp, url)
    return {"ok": True, "appId": APPID, "timestamp": timestamp,
            "nonceStr": noncestr, "signature": signature, "jsapiTicket": ticket[:10] + "..."}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/sign":
            query = urllib.parse.parse_qs(parsed.query)
            try:
                result = handle_sign(query)
            except Exception as e:  # noqa: BLE001
                result = {"ok": False, "error": str(e)}
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = b'{"ok":false,"error":"not found"}'
            self.send_response(404)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    def log_message(self, fmt, *args):  # 精简日志
        print("[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args))


if __name__ == "__main__":
    if not APPID or not SECRET:
        print("警告: WX_APPID/WX_SECRET 未配置，签名接口将返回错误")
    print("微信 JS-SDK 签名服务启动: http://%s:%d/sign" % (HOST, PORT))
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
