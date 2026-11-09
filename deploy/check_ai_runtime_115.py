# -*- coding: utf-8 -*-
"""只读检查 115 部署后的 AI 运行日志与最近审计记录。"""
import json
import shlex

import paramiko

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
MYSQL = "mysql -usadmin -p'Chinaunicom@1358' netsight -N -B -e"


def run(cli, cmd, timeout=60):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return out.read().decode("utf-8", "ignore").strip() + err.read().decode("utf-8", "ignore").strip()


def sql(cli, query):
    return run(cli, "%s %s 2>/dev/null" % (MYSQL, shlex.quote(query)))


def main():
    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)
    try:
        print("== 最近 AI 日志 ==")
        print(run(cli, "grep -E 'AI 问诊|knowledge_ask|AIService|CloudModel' /opt/nams-server/app.log "
                       "| tail -n 30 || true"))
        print("== 最近 ERROR/Exception ==")
        print(run(cli, "grep -E 'ERROR|Exception' /opt/nams-server/app.log | tail -n 20 || true"))
        print("== 最近审计 ==")
        print(sql(cli, "SELECT id,tenant_id,user_id,order_id,LEFT(request_id,8),latency_ms,prompt_version,status "
                       "FROM ai_usage_log ORDER BY id DESC LIMIT 5;"))
    finally:
        cli.close()


if __name__ == "__main__":
    main()
