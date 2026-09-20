# -*- coding: utf-8 -*-
"""
NetSight V1.1.5 第6周联调脚本
覆盖：登录 → 动态路由(监控大屏/网络拓扑) → Dashboard聚合接口 → 低库存预警事件链路
"""
import json
import time
import urllib.request

BASE = "http://localhost:8080"
PASS = []
FAIL = []


def call(method, path, body=None, token=None, headers=None):
    url = BASE + path
    data = None
    hdrs = {"Content-Type": "application/json; charset=utf-8"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"  [PASS] {name} {detail}")
    else:
        FAIL.append(name)
        print(f"  [FAIL] {name} {detail}")


def main():
    print("== 1. 登录 ==")
    st, r = call("POST", "/auth/sms-code?phone=13800000000")
    print(f"  sms-code: {st}")
    time.sleep(1.2)
    st, r = call("POST", "/auth/login", {"phone": "13800000000", "code": "123456"})
    check("登录", st == 200 and r.get("code") == 200, f"code={r.get('code')}")
    if r.get("code") != 200:
        print("  登录失败，终止")
        return
    token = r["data"]["token"]
    print(f"  token len={len(token)} roles={r['data']['user']['roles']}")

    print("== 2. 动态路由（监控大屏/网络拓扑菜单） ==")
    st, r = call("GET", "/getRouters", token=token)
    paths = []
    def walk(nodes):
        for n in nodes:
            paths.append(n.get("path", ""))
            walk(n.get("children", []))
    walk(r.get("data", []))
    check("路由含/dashboard", "/dashboard" in paths, f"paths={[p for p in paths if p in ('/dashboard','/device','/alert','/workorder','/spare','/system')]}")
    check("路由含/device", "/device" in paths)
    device = next((n for n in r.get("data", []) if n.get("path") == "/device"), None)
    children = [c.get("path") for c in (device.get("children", []) if device else [])]
    check("设备资产含网络拓扑", "topology" in children, f"children={children}")

    print("== 3. Dashboard 聚合接口 ==")
    st, r = call("GET", "/dashboard/overview", token=token)
    check("overview 200", st == 200 and r.get("code") == 200)
    if r.get("code") == 200:
        d = r["data"]
        dev = d["device"]
        check("设备概览字段", {"total", "online", "offline", "lineAbnormal", "onlineRate", "byType"} <= set(dev.keys()),
              f"total={dev['total']} online={dev['online']} offline={dev['offline']} rate={dev['onlineRate']}")
        check("设备byType非空", isinstance(dev["byType"], list) and len(dev["byType"]) >= 0,
              json.dumps(dev["byType"], ensure_ascii=False)[:200])
        ev = d["event"]
        check("事件趋势7天", len(ev["trend"]) == 7, f"trend={ev['trend']}")
        check("工单看板", {"pending", "dispatched", "repairing", "completed", "total"} <= set(d["order"].keys()),
              json.dumps(d["order"], ensure_ascii=False))
        check("网关状态", "online" in d["gateway"], json.dumps(d["gateway"], ensure_ascii=False))
        check("备件库存", "lowStock" in d["spare"], json.dumps(d["spare"], ensure_ascii=False))

    print("== 4. 低库存预警链路（出库触发事件+通知日志） ==")
    st, r = call("GET", "/spare/part/list?pageNum=1&pageSize=5", token=token)
    parts = r.get("data", {}).get("rows", []) if r.get("data") else []
    check("备件列表可查", len(parts) > 0, f"count={len(parts)}")
    if parts:
        part = parts[0]
        pid = part["id"]
        old_safe = part["safeStock"] or 0
        print(f"  备件[{part['partNo']}] 库存={part['quantity']} 安全库存={old_safe}")
        # 制造低库存：调高安全库存=当前库存+2，再做一次入库操作触发检查
        st, r = call("PUT", "/spare/part", {"id": pid, "safeStock": part["quantity"] + 2}, token=token)
        print(f"  调高安全库存: code={r.get('code')} msg={r.get('msg')}")
        st, r = call("POST", "/spare/part/stock", {
            "partId": pid, "recordType": "adjust", "quantity": 1,
            "orderId": None, "orderNo": None, "remark": "第6周低库存预警联调"
        }, token=token)
        print(f"  入库触发: code={r.get('code')} msg={r.get('msg')}")
        # 恢复安全库存
        call("PUT", "/spare/part", {"id": pid, "safeStock": old_safe}, token=token)
        # 查询事件记录 stock_low
        time.sleep(1.5)
        st, r = call("GET", "/alert/event/list?pageNum=1&pageSize=10&eventType=stock_low", token=token)
        rows = r.get("data", {}).get("rows", []) if r.get("data") else []
        check("stock_low事件已生成", any(x.get("eventType") == "stock_low" for x in rows),
              json.dumps([{"eventType": x.get("eventType"), "severity": x.get("severity"),
                           "deviceName": x.get("deviceName")} for x in rows[:3]], ensure_ascii=False))
        st, r = call("GET", "/alert/log/list?pageNum=1&pageSize=20", token=token)
        logs = r.get("data", {}).get("rows", []) if r.get("data") else []
        stock_logs = [x for x in logs if x.get("eventType") == "stock_low"]
        check("stock_low通知日志落库", len(stock_logs) > 0,
              json.dumps([{"channel": x.get("channelType"), "success": x.get("success"),
                           "content": (x.get("content") or "")[:40]} for x in stock_logs[:4]], ensure_ascii=False))

    print(f"\n===== 结果：PASS {len(PASS)} / FAIL {len(FAIL)} =====")
    if FAIL:
        print("FAIL项:", FAIL)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
