# -*- coding: utf-8 -*-
"""V1.1.6 租户级 Webhook Token 云端联调测试（paramiko + 云端 curl）
覆盖：
  1. 登录 → 租户列表脱敏 → 明文 Token
  2. 租户 Token 推送成功（事件归属租户 1）
  3. 重置 Token → 旧 Token 401 / 新 Token 200
  4. 全局 webhook-token 过渡兜底仍可用
  5. 错误/缺失 Token → 401
  6. Redis 缓存命中验证
  7. 清理测试数据（回演示基线）
"""
import json
import time
import paramiko

HOST = "192.168.1.55"
BASE = "http://127.0.0.1/prod-api"
PHONE = "13800000000"
GLOBAL_TOKEN = "NetSightWebhookToken2026"

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
cli.connect(HOST, username="root", password="Chinaunicom@1358", timeout=15)

PASS = 0
FAIL = 0


def run(cmd, timeout=60):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [PASS] %s%s" % (name, (" | " + extra) if extra else ""))
    else:
        FAIL += 1
        print("  [FAIL] %s%s" % (name, (" | " + extra) if extra else ""))


def curl_post(path, headers=None, data_file=None):
    hdrs = " ".join('-H "%s: %s"' % (k, v) for k, v in (headers or {}).items())
    if data_file:
        cmd = "curl -s -X POST -H 'Content-Type: application/json' %s -d @%s %s%s" % (hdrs, data_file, BASE, path)
    else:
        cmd = "curl -s -X POST -H 'Content-Type: application/json' %s %s%s" % (hdrs, BASE, path)
    return run(cmd)


def curl_get(path, headers=None):
    hdrs = " ".join('-H "%s: %s"' % (k, v) for k, v in (headers or {}).items())
    return run("curl -s %s %s%s" % (hdrs, BASE, path))


def upload_json(name, obj):
    remote = "/tmp/%s" % name
    sftp = cli.open_sftp()
    with sftp.open(remote, "w") as f:
        f.write(json.dumps(obj, ensure_ascii=False))
    sftp.close()
    return remote


try:
    # ---------- 1. 登录 ----------
    print("==> 1. 登录")
    # /auth/sms-code 是 @RequestParam phone（query 参数）
    resp = run("curl -s -X POST '%s/auth/sms-code?phone=%s'" % (BASE, PHONE))
    print("  sms-code 响应:", resp)
    time.sleep(11)
    login_body = json.dumps({"phone": PHONE, "code": "123456", "clientType": "pc"})
    login_file = upload_json("v116_login.json", {"phone": PHONE, "code": "123456", "clientType": "pc"})
    resp = json.loads(curl_post("/auth/login", data_file=login_file))
    check("登录成功", resp.get("code") == 200, "code=%s" % resp.get("code"))
    token = resp.get("data", {}).get("token")
    H = {"Authorization": "Bearer " + token}

    # ---------- 2. 租户列表脱敏 + 明文 Token ----------
    print("==> 2. 租户列表 / 明文 Token")
    resp = json.loads(curl_get("/system/tenant/list?pageNum=1&pageSize=10", H))
    rows = resp.get("data", {}).get("rows", [])
    t1 = next((r for r in rows if r["id"] == 1), None)
    masked = t1.get("webhookToken", "")
    check("列表 Token 已脱敏(6+******+4)", masked.endswith("******") or len(masked) < 32,
          "masked=%s" % masked)
    resp = json.loads(curl_get("/system/tenant/1/webhook-token", H))
    tenant_token = resp.get("data")
    check("明文 Token 32 位", isinstance(tenant_token, str) and len(tenant_token) == 32,
          "token=%s" % (tenant_token[:8] + "..." if tenant_token else None))

    # ---------- 3. 租户 Token 推送 ----------
    print("==> 3. 租户 Token 推送（事件归属租户1）")
    payload = {
        "status": "firing",
        "commonLabels": {"severity": "critical", "alertname": "网络设备离线故障",
                         "device_ip": "192.168.10.9", "device_name": "V116联调设备",
                         "device_type": "network", "device_location": "联调机房"},
        "alerts": [{
            "status": "firing",
            "labels": {"alertname": "网络设备离线故障", "severity": "critical",
                       "device_ip": "192.168.10.9", "device_name": "V116联调设备"},
            "annotations": {"summary": "设备V116联调设备(192.168.10.9)离线故障",
                            "description": "V1.1.6 租户级Webhook Token联调-1"},
            "generatorURL": "http://gateway:9090/graph?g0.expr=v116_case1"
        }]
    }
    pf = upload_json("v116_payload1.json", payload)
    resp = json.loads(curl_post("/alert/push", {"X-Netsight-Webhook-Token": tenant_token}, pf))
    check("租户 Token 推送 200", resp.get("code") == 200, "code=%s msg=%s" % (resp.get("code"), resp.get("msg")))
    event_ids = resp.get("data", [])
    time.sleep(2)  # 等异步处理
    resp = json.loads(curl_get("/alert/event/list?pageNum=1&pageSize=5", H))
    evs = resp.get("data", {}).get("rows", [])
    ev = next((e for e in evs if e.get("deviceIp") == "192.168.10.9"), None)
    check("事件已入库且归属租户1", ev is not None and ev.get("tenantId") == 1,
          "tenantId=%s eventType=%s" % (ev.get("tenantId") if ev else None, ev.get("eventType") if ev else None))
    check("事件状态已发送", ev is not None and ev.get("status") == 1,
          "status=%s" % (ev.get("status") if ev else None))

    # ---------- 4. 重置 Token ----------
    print("==> 4. 重置 Token：旧失效 / 新生效")
    resp = json.loads(curl_post("/system/tenant/1/webhook-token/reset", H))
    new_token = resp.get("data")
    check("重置返回新 Token 32 位", isinstance(new_token, str) and len(new_token) == 32)
    check("新 Token 与旧不同", new_token != tenant_token)

    # 旧 Token 推送 → 401
    resp = json.loads(curl_post("/alert/push", {"X-Netsight-Webhook-Token": tenant_token}, pf))
    check("旧 Token 推送被拒 401", resp.get("code") == 401, "code=%s" % resp.get("code"))
    # 新 Token 推送 → 200
    payload["alerts"][0]["generatorURL"] = "http://gateway:9090/graph?g0.expr=v116_case2"
    pf2 = upload_json("v116_payload2.json", payload)
    resp = json.loads(curl_post("/alert/push", {"X-Netsight-Webhook-Token": new_token}, pf2))
    check("新 Token 推送 200", resp.get("code") == 200, "code=%s" % resp.get("code"))

    # ---------- 5. 全局兜底 + 错误 Token ----------
    print("==> 5. 全局兜底 / 错误 Token")
    payload["alerts"][0]["generatorURL"] = "http://gateway:9090/graph?g0.expr=v116_case3"
    pf3 = upload_json("v116_payload3.json", payload)
    resp = json.loads(curl_post("/alert/push", {"X-Netsight-Webhook-Token": GLOBAL_TOKEN,
                                                "X-Netsight-Tenant-Id": "1"}, pf3))
    check("全局 Token 过渡兜底 200", resp.get("code") == 200, "code=%s" % resp.get("code"))
    resp = json.loads(curl_post("/alert/push", {"X-Netsight-Webhook-Token": "wrong-token-xxx"}, pf3))
    check("错误 Token 401", resp.get("code") == 401, "code=%s" % resp.get("code"))
    resp = json.loads(curl_post("/alert/push", {}, pf3))
    check("缺失 Token 401", resp.get("code") == 401, "code=%s" % resp.get("code"))

    # ---------- 6. Redis 缓存 ----------
    print("==> 6. Redis 缓存")
    r = run("redis-cli -n 0 get 'netsight:webhook:token:%s'" % new_token)
    check("Redis 已缓存新 Token→租户1", r == "1", "value=%s" % r)
    r = run("redis-cli -n 0 exists 'netsight:webhook:token:%s'" % tenant_token)
    check("旧 Token 缓存已清除", r == "0", "exists=%s" % r)

    # ---------- 7. 清理测试数据 ----------
    print("==> 7. 清理测试数据（device_ip=192.168.10.9）")
    clean = run("""mysql -usadmin -p'Chinaunicom@1358' netsight -e "
DELETE l FROM notification_log l JOIN event_record e ON l.event_id=e.id WHERE e.device_ip='192.168.10.9';
DELETE wpr FROM work_order_part wpr JOIN work_order wo ON wpr.order_id=wo.id WHERE wo.device_ip='192.168.10.9';
DELETE wr FROM work_order_record wr JOIN work_order wo ON wr.order_id=wo.id WHERE wo.device_ip='192.168.10.9';
DELETE FROM work_order WHERE device_ip='192.168.10.9';
DELETE FROM event_record WHERE device_ip='192.168.10.9';
SELECT (SELECT COUNT(*) FROM event_record) AS ev_cnt, (SELECT COUNT(*) FROM work_order) AS wo_cnt, (SELECT COUNT(*) FROM notification_log) AS log_cnt;
" 2>&1""")
    print("  ", clean.replace("\n", " | "))

    print("\n===== 联调结果: PASS=%d FAIL=%d =====" % (PASS, FAIL))
finally:
    cli.close()
