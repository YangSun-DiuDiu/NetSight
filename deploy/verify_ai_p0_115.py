# -*- coding: utf-8 -*-
"""AI 诊断 P0 改造验收脚本（测试环境 115.159.194.149，只读 + 一次真实问诊）

用法：
    python verify_ai_p0_115.py            # 含端到端问诊（会消耗 1 次模型调用/额度）
    python verify_ai_p0_115.py --no-e2e   # 只做结构/配置/权限/产物检查

检查项：
    1) ai_usage_log 新增 5 个审计字段
    2) 权限 ai:diagnose 存在且已授给 repairer(role_id=4)
    3) 租户 7 知识库条目 > 0
    4) nams.env 的 AI 超时/重试/脱敏/日额度配置
    5) 线上 jar 含 AiController、前端产物含免责声明
    6) 健康检查
    7) H5 维修员登录 + POST /ai/knowledge/ask 真实调用（可选）
"""
import argparse
import json
import shlex
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import paramiko


def http_json(url, method="GET", payload=None, token=None, timeout=45):
    data = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
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

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
BASE = "http://115.159.194.149/prod-api"
PHONE = "15657477316"
MOCK_CODE = "123456"
MYSQL = "mysql -usadmin -p'Chinaunicom@1358' netsight -N -B -e"


def run(cli, cmd, timeout=90):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


def sql(cli, query):
    cmd = "%s %s 2>/dev/null" % (MYSQL, shlex.quote(query))
    _, out, _ = cli.exec_command(cmd, timeout=90)
    return out.read().decode("utf-8", "ignore").strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-e2e", action="store_true")
    args = parser.parse_args()

    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)

    failures = []
    notes = []

    def check(name, ok, detail=""):
        print(("[PASS] " if ok else "[FAIL] ") + name + ((" -> " + detail) if detail else ""))
        if not ok:
            failures.append(name + ((" -> " + detail) if detail else ""))

    try:
        # 1. 审计字段
        cols = sql(cli, "SELECT COUNT(*) FROM information_schema.columns "
                        "WHERE table_schema='netsight' AND table_name='ai_usage_log' "
                        "AND column_name IN ('user_id','order_id','request_id','latency_ms','prompt_version');")
        check("ai_usage_log 审计字段 5 个", cols.strip() == "5", "实际=" + cols)

        # 2. 权限
        perm = sql(cli, "SELECT COUNT(*) FROM sys_permission WHERE perm_key='ai:diagnose';")
        check("ai:diagnose 权限节点存在", perm.strip() == "1", "count=" + perm)
        grant = sql(cli, "SELECT COUNT(*) FROM sys_role_permission srp "
                         "JOIN sys_permission p ON p.id=srp.permission_id "
                         "WHERE srp.role_id=4 AND p.perm_key='ai:diagnose';")
        check("ai:diagnose 已授 repairer(role_id=4)", grant.strip() == "1", "count=" + grant)

        # 3. 知识库
        kn = sql(cli, "SELECT COUNT(*) FROM fault_article WHERE tenant_id=7 AND status=1 AND del_flag=0;")
        check("租户 7 知识库条目 > 0", kn.strip().isdigit() and int(kn) > 0, "count=" + kn)

        # 4. nams.env
        env = run(cli, "grep -E '^(AI_MASK_SENSITIVE|AI_MAX_RETRIES|AI_READ_TIMEOUT_MS|"
                       "AI_CONNECT_TIMEOUT_MS|AI_DAILY_CALL_LIMIT)=' /opt/nams-server/nams.env || true")
        print("nams.env AI 配置：\n" + (env if env else "(未显式配置，使用 application.yml 默认)"))
        check("nams.env 已显式设置 AI_MASK_SENSITIVE=true", "AI_MASK_SENSITIVE=true" in env)
        check("nams.env 已显式设置 AI_MAX_RETRIES=0", "AI_MAX_RETRIES=0" in env)
        check("nams.env 已显式设置 AI_READ_TIMEOUT_MS=15000", "AI_READ_TIMEOUT_MS=15000" in env)

        # 5. 产物
        jar = run(cli, "jar tf /opt/nams-server/netsight-server.jar 2>/dev/null | "
                       "grep -c 'modules/ai/controller/AiController.class' || true")
        check("线上 jar 含 AiController", jar.strip() == "1", "count=" + jar)
        front = run(cli, "grep -R -l 'AI 建议仅供参考' /opt/nams-ui/dist/static/js 2>/dev/null | wc -l")
        check("前端产物含 AI 免责声明", front.strip().isdigit() and int(front) > 0, "files=" + front)
        front_to = run(cli, "grep -R -l 'AI 响应超时' /opt/nams-ui/dist/static/js 2>/dev/null | wc -l")
        check("前端产物含 AI 超时提示", front_to.strip().isdigit() and int(front_to) > 0, "files=" + front_to)

        # 6. 健康
        health = run(cli, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000")
        check("后端健康检查 200", health.strip() == "200", "http=" + health)

        # 7. 端到端
        if not args.no_e2e:
            qs = urllib.parse.urlencode({"phone": PHONE})
            status_code, sj = http_json(BASE + "/auth/sms-code?" + qs, method="POST", timeout=15)
            print("sms-code: http=%s code=%s msg=%s" % (status_code, sj.get("code"), sj.get("msg")))
            if sj.get("code") != 200:
                check("H5 验证码发送", False, str(sj)[:200])
            else:
                status_code, lj = http_json(
                    BASE + "/auth/login", method="POST",
                    payload={"phone": PHONE, "code": MOCK_CODE, "clientType": "m"},
                    timeout=20)
                print("login: http=%s code=%s msg=%s" % (status_code, lj.get("code"), lj.get("msg")))
                token = (lj.get("data") or {}).get("token")
                check("H5 维修员登录拿到 token", bool(token), "http=%s code=%s" % (status_code, lj.get("code")))
                if token:
                    unique = "AI诊断P0验收%d 摄像头离线 指示灯不亮 网络不通" % int(time.time())
                    payload = {
                        "question": unique,
                        "orderId": 0,
                        "deviceName": "测试摄像头",
                        "deviceType": "camera",
                        "deviceLocation": "测试机房",
                        "faultType": "device_offline",
                        "description": "摄像头离线，指示灯不亮，网络不通"
                    }
                    status_code, aj = http_json(
                        BASE + "/ai/knowledge/ask", method="POST",
                        payload=payload, token=token, timeout=45)
                    data = aj.get("data") or {}
                    print("ai/knowledge/ask: http=%s code=%s msg=%s aiUsed=%s noHit=%s sources=%s" % (
                        status_code, aj.get("code"), aj.get("msg"),
                        data.get("aiUsed"), data.get("noHit"), len(data.get("sources") or [])))
                    if status_code != 200 or aj.get("code") != 200:
                        print("响应体：", str(aj)[:500])
                    check("AI 问诊接口返回 200", status_code == 200 and aj.get("code") == 200,
                          str(aj)[:250])
                    check("AI 问诊有答案且非 noHit", bool(data.get("answer")) and not data.get("noHit"),
                          "aiUsed=%s" % data.get("aiUsed"))
                    if data.get("aiUsed") is False:
                        notes.append("本次为降级/额度/熔断返回知识库原文，属于设计内 fallback，请结合 ai_usage_log 状态判断")
                    latest = sql(cli, "SELECT user_id,order_id,request_id,latency_ms,prompt_version,status "
                                      "FROM ai_usage_log ORDER BY id DESC LIMIT 1;")
                    print("ai_usage_log 最新：", latest.replace("\t", " | "))
                    check("ai_usage_log 最新记录含 request_id/prompt_version",
                          "knowledge_ask_v1" in latest and latest.count("\t") >= 5, latest[:200])
    finally:
        cli.close()

    print("\n===== 汇总 =====")
    if notes:
        for n in notes:
            print("[NOTE] " + n)
    if failures:
        print("失败项 %d 个：" % len(failures))
        for f in failures:
            print(" - " + f)
        sys.exit(1)
    print("全部检查通过")


if __name__ == "__main__":
    main()
