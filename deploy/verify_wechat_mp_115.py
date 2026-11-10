#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NetSight 服务号全面支持：115 测试环境只读验收脚本

用法（PowerShell）：
  $env:NAMS_SSH_PASSWORD='...'
  python deploy/verify_wechat_mp_115.py

特性：
  - 只读：不写数据库、不改文件、不发消息；
  - 输出结构化检查结果（PASS / WARN / FAIL）；
  - 退出码 0 表示硬性检查全部通过，1 表示存在硬性失败。
"""
from __future__ import annotations

import base64
import json
import os
import sys
from dataclasses import dataclass
from typing import Any

import paramiko

HOST = os.environ.get("NAMS_SSH_HOST", "115.159.194.149")
PORT = int(os.environ.get("NAMS_SSH_PORT", "22"))
USER = os.environ.get("NAMS_SSH_USER", "root")
PASSWORD = os.environ.get("NAMS_SSH_PASSWORD") or os.environ.get("SSH_PASSWORD")
REMOTE_ENV = "/opt/nams-server/nams.env"
REMOTE_APP_LOG = "/opt/nams-server/app.log"

EXPECTED_TEMPLATE_CODES = [
    "tpl_device_offline",
    "tpl_device_line",
    "tpl_device_recovered",
    "tpl_device_cpu_high",
    "tpl_device_mem_high",
    "tpl_order_dispatch",
    "tpl_stock_low",
    "tpl_manual",
]
WECHAT_TEMPLATE_ID = "-AItaLZ-5aAGMOxWJ4HHZ5y2AE0rt1DElL8nU0eS8-U"


@dataclass
class Result:
    name: str
    status: str
    detail: str


class Client:
    def __init__(self) -> None:
        if not PASSWORD:
            raise SystemExit("缺少 NAMS_SSH_PASSWORD / SSH_PASSWORD 环境变量")
        self.ssh = paramiko.SSHClient()
        self.ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self.ssh.connect(HOST, PORT, USER, PASSWORD, timeout=20, banner_timeout=30, auth_timeout=30)

    def close(self) -> None:
        self.ssh.close()

    def run(self, cmd: str, timeout: int = 120) -> tuple[int, str, str]:
        _, out, err = self.ssh.exec_command(cmd, timeout=timeout)
        text = out.read().decode("utf-8", "replace").strip()
        errtext = err.read().decode("utf-8", "replace").strip()
        return out.channel.recv_exit_status(), text, errtext

    def mysql(self, sql: str) -> list[list[str]]:
        payload = base64.b64encode(sql.encode("utf-8")).decode("ascii")
        cmd = (
            "set -e; set -a; . " + REMOTE_ENV + "; set +a; "
            f"echo {payload} | base64 -d | "
            "MYSQL_PWD=\"${MYSQL_PASSWORD:-$MYSQL_PASS}\" mysql -N -B "
            "-h \"${MYSQL_HOST:-127.0.0.1}\" -P \"${MYSQL_PORT:-3306}\" "
            "-u \"${MYSQL_USER:-root}\" \"${MYSQL_DB:-netsight}\""
        )
        code, out, err = self.run(cmd, timeout=60)
        if code != 0:
            raise RuntimeError(err or out or f"mysql exit {code}")
        rows: list[list[str]] = []
        for line in out.splitlines():
            if line.strip():
                rows.append(line.split("\t"))
        return rows


def add(results: list[Result], name: str, status: str, detail: str) -> None:
    results.append(Result(name, status, detail))


def main() -> int:
    c = Client()
    results: list[Result] = []
    try:
        # 0. 环境确认
        code, out, err = c.run("hostname; date '+%F %T'; uptime | head -1")
        if code == 0:
            add(results, "115 环境可达", "PASS", out.replace("\n", " | "))
        else:
            add(results, "115 环境可达", "FAIL", err or "ssh failed")

        # 1. 健康检查
        code, out, _ = c.run("curl -s -o /tmp/health.out -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true")
        if out == "200":
            _, body, _ = c.run("cat /tmp/health.out")
            add(results, "1. 后端健康检查", "PASS", f"HTTP 200 {body[:120]}")
        else:
            add(results, "1. 后端健康检查", "FAIL", f"HTTP {out or 'N/A'}")

        # 2. 新增列
        log_cols = c.mysql(
            "SELECT column_name, data_type FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='notification_log' "
            "AND column_name IN ('channel_id','template_id','recipient_results_json') ORDER BY column_name;"
        )
        contact_cols = c.mysql(
            "SELECT column_name, data_type FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='notify_contact' "
            "AND column_name IN ('wechat_app_id','wechat_bound_at') ORDER BY column_name;"
        )
        expected_log = {"channel_id", "template_id", "recipient_results_json"}
        got_log = {r[0] for r in log_cols if len(r) >= 2}
        expected_contact = {"wechat_app_id", "wechat_bound_at"}
        got_contact = {r[0] for r in contact_cols if len(r) >= 2}
        if expected_log == got_log and expected_contact == got_contact:
            add(results, "2. 新增列存在", "PASS", f"log={sorted(got_log)}; contact={sorted(got_contact)}")
        else:
            add(results, "2. 新增列存在", "FAIL", f"log缺={sorted(expected_log - got_log)}; contact缺={sorted(expected_contact - got_contact)}")

        # 3. 模板 43 JSON
        rows = c.mysql(
            "SELECT id, tenant_id, channel_id, enabled, content FROM notification_template "
            "WHERE id=43 AND tenant_id=7;"
        )
        if rows and len(rows[0]) >= 5:
            content = rows[0][4]
            try:
                desc = json.loads(content)
                fields = desc.get("fields") or []
                ok = (
                    desc.get("templateId") == WECHAT_TEMPLATE_ID
                    and len(fields) >= 5
                    and all(f.get("key") and f.get("value") for f in fields)
                )
                add(results, "3. 模板43描述符JSON", "PASS" if ok else "FAIL",
                    f"templateId={desc.get('templateId','')[:10]}... fields={len(fields)} enabled={rows[0][3]}")
            except Exception as e:
                add(results, "3. 模板43描述符JSON", "FAIL", f"JSON解析失败: {e}")
        else:
            add(results, "3. 模板43描述符JSON", "FAIL", "模板43不存在或字段不足")

        # 4. 7 条新模板是否存在且默认停用
        rows = c.mysql(
            "SELECT template_code, enabled, channel_id, LENGTH(content) FROM notification_template "
            "WHERE tenant_id=7 AND channel_id=7 AND template_code IN ("
            "'tpl_device_line','tpl_device_recovered','tpl_device_cpu_high','tpl_device_mem_high',"
            "'tpl_order_dispatch','tpl_stock_low','tpl_manual') ORDER BY template_code;"
        )
        got_codes = {r[0] for r in rows}
        expected_new = set(EXPECTED_TEMPLATE_CODES) - {"tpl_device_offline"}
        disabled_ok = all(r[1] == "0" for r in rows)
        if expected_new == got_codes and disabled_ok:
            add(results, "4. 7条新模板已建且停用", "PASS", f"codes={sorted(got_codes)}")
        else:
            add(results, "4. 7条新模板已建且停用", "FAIL",
                f"缺少={sorted(expected_new - got_codes)}; enabled={[(r[0], r[1]) for r in rows]}")

        # 5. 全部 8 类模板编码齐备
        rows = c.mysql(
            "SELECT DISTINCT template_code FROM notification_template "
            "WHERE tenant_id=7 AND channel_type='wechat' AND template_code IN ("
            + ",".join("'" + x + "'" for x in EXPECTED_TEMPLATE_CODES) + ");"
        )
        codes = {r[0] for r in rows}
        if set(EXPECTED_TEMPLATE_CODES) == codes:
            add(results, "5. 8类微信模板编码齐备", "PASS", ",".join(sorted(codes)))
        else:
            add(results, "5. 8类微信模板编码齐备", "FAIL", f"缺少={sorted(set(EXPECTED_TEMPLATE_CODES) - codes)}")

        # 6. 微信通道实例关键配置（不输出 AppSecret）
        rows = c.mysql(
            "SELECT id, enabled, health_status, config_json FROM notify_channel "
            "WHERE id=7 AND tenant_id=7;"
        )
        if rows and len(rows[0]) >= 4:
            try:
                cfg = json.loads(rows[0][3] or "{}")
                keys_ok = all(k in cfg for k in ("appId", "appSecret", "templateId"))
                add(results, "6. 微信通道配置", "PASS" if keys_ok else "FAIL",
                    f"enabled={rows[0][1]} health={rows[0][2]} keys={sorted(cfg.keys())}")
            except Exception as e:
                add(results, "6. 微信通道配置", "FAIL", f"config_json解析失败: {e}")
        else:
            add(results, "6. 微信通道配置", "FAIL", "通道实例 id=7 不存在")

        # 7. 已绑定联系人
        rows = c.mysql("SELECT COUNT(*) FROM notify_contact WHERE tenant_id=7 AND status=1 AND wechat_openid IS NOT NULL AND wechat_openid<>'';")
        count = int(rows[0][0]) if rows else 0
        add(results, "7. 已绑定openid联系人", "PASS" if count > 0 else "WARN",
            f"tenant7已绑定={count}（无绑定时微信发送会明确失败，不阻断工单）")

        # 8. 日志逐人结果字段可解析（取部署后最近的微信日志）
        rows = c.mysql(
            "SELECT id, event_type, success, channel_id, template_id, "
            "LEFT(COALESCE(recipient_results_json,''), 4000) FROM notification_log "
            "WHERE channel_type='wechat' AND channel_id IS NOT NULL "
            "ORDER BY id DESC LIMIT 10;"
        )
        parsed = 0
        recipient_total = 0
        for r in rows:
            if len(r) < 6 or not r[5]:
                continue
            try:
                arr = json.loads(r[5])
            except Exception:
                continue
            if isinstance(arr, list):
                parsed += 1
                recipient_total += len(arr)
        if rows and parsed == len(rows):
            add(results, "8. 最近微信日志逐人结果", "PASS", f"logs={len(rows)} parsed={parsed} recipients={recipient_total}")
        elif rows:
            add(results, "8. 最近微信日志逐人结果", "WARN", f"logs={len(rows)} parsed={parsed}（旧日志可能无逐人结果）")
        else:
            add(results, "8. 最近微信日志逐人结果", "WARN", "暂无带 channel_id 的微信日志，需触发一次发送后复核")

        # 9. 旧空 content 不再出现在启用模板
        rows = c.mysql(
            "SELECT id, template_code FROM notification_template "
            "WHERE tenant_id=7 AND channel_type='wechat' AND enabled=1 AND (content IS NULL OR TRIM(content)='');"
        )
        if not rows:
            add(results, "14. 启用中空content模板", "PASS", "无启用但空内容的微信模板")
        else:
            add(results, "14. 启用中空content模板", "FAIL", f"{rows}")

        # 10. 进程与启动脚本
        code, out, _ = c.run("ps -ef | grep '[n]etsight-server'")
        add(results, "9. 后端进程", "PASS" if "netsight-server.jar" in out else "FAIL", out.splitlines()[0] if out else "未找到进程")

        # 11. 启动日志近期无 ERROR（只看最近 80 行，避免历史噪音）
        code, out, _ = c.run(f"tail -n 80 {REMOTE_APP_LOG} | grep -E 'ERROR|Exception' | tail -20 || true")
        if out:
            add(results, "10. 启动日志", "WARN", "最近80行存在异常: " + out.replace("\n", " / ")[:800])
        else:
            add(results, "10. 启动日志", "PASS", "最近80行无 ERROR/Exception")

        # 12. 前端入口存在
        code, out, _ = c.run("test -f /opt/nams-ui/dist/index.html && echo OK; ls -ld /opt/nams-ui/dist")
        add(results, "11. 前端产物", "PASS" if "OK" in out else "FAIL", out.replace("\n", " | "))

        # 13. OAuth 回调基址配置（从应用环境/配置只读检查，不输出密钥）
        code, out, _ = c.run(
            "grep -E '^(WECHAT_OAUTH_PUBLIC_BASE_URL|WECHAT_JUMP_URL|WECHAT_TIMEOUT_MS)=' "
            + REMOTE_ENV + " || true"
        )
        add(results, "12. 微信OAuth/跳转配置", "PASS" if "WECHAT_OAUTH_PUBLIC_BASE_URL" in out else "WARN",
            out.replace("\n", " | ") or "nams.env 未显式配置，使用 application.yml 默认值")

        # 14. 模板字段映射完整性（描述符字段 key 合法）
        rows = c.mysql(
            "SELECT id, template_code, content FROM notification_template "
            "WHERE tenant_id=7 AND channel_type='wechat' ORDER BY id;"
        )
        bad = []
        for r in rows:
            try:
                desc = json.loads(r[2])
                for f in desc.get("fields") or []:
                    if not (f.get("key") or "").replace("_", "").isalnum():
                        bad.append((r[0], f.get("key")))
            except Exception as e:
                bad.append((r[0], f"json:{e}"))
        add(results, "13. 微信模板字段key合法", "PASS" if not bad else "FAIL", str(bad) if bad else f"检查{len(rows)}条模板")

        # 15. 验收缺口说明（只读脚本无法自动触发真实发送/扫码）
        add(results, "15. 需人工联动项", "WARN",
            "真实发送（manual/离线/派单/库存）、频控、43004、扫码绑定与解绑需登录前端或触发 Webhook 后复核")
    finally:
        c.close()

    print("\n" + "=" * 78)
    print("NetSight 服务号全面支持 · 115 只读验收")
    print("=" * 78)
    width = max(len(r.name) for r in results) + 2
    for r in results:
        print(f"{r.status:<5} {r.name:<{width}} {r.detail}")
    failures = [r for r in results if r.status == "FAIL"]
    print("=" * 78)
    print(f"合计 {len(results)} 项：PASS={sum(r.status == 'PASS' for r in results)}，"
          f"WARN={sum(r.status == 'WARN' for r in results)}，FAIL={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
