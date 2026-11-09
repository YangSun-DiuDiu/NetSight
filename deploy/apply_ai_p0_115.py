# -*- coding: utf-8 -*-
"""在测试环境 115 执行 AI P0 SQL 迁移并更新 nams.env（不重启应用）。"""
import json
import os
import shlex
import sys
from datetime import datetime

import paramiko

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
WT = r"D:\gitee\worktrees\NetSight1.0-repairer-user-account-binding"
MIGRATION = os.path.join(WT, "docs", "sql", "ai_p0_migration.sql")
DATA = os.path.join(WT, "docs", "sql", "ai_p0_data.sql")
ENV_PATH = "/opt/nams-server/nams.env"
STAMP = datetime.now().strftime("%Y%m%d-%H%M%S")
MYSQL = "mysql -usadmin -p'Chinaunicom@1358' netsight"


def run(cli, cmd, timeout=90):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


def sql(cli, query):
    return run(cli, MYSQL + " -N -B -e " + shlex.quote(query))


def main():
    for p in (MIGRATION, DATA):
        if not os.path.exists(p):
            raise SystemExit("missing: " + p)

    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)
    sftp = cli.open_sftp()
    try:
        backup = "/opt/nams-server/ai-p0-backup-%s.sql" % STAMP
        print("==> 备份相关表 ->", backup)
        out = run(cli, "mysqldump -usadmin -p'Chinaunicom@1358' netsight "
                       "ai_usage_log fault_article sys_permission sys_role_permission > %s && ls -l %s" % (
                           backup, backup), timeout=120)
        print(out)

        cols = sql(cli, "SELECT COUNT(*) FROM information_schema.columns "
                        "WHERE table_schema='netsight' AND table_name='ai_usage_log' "
                        "AND column_name IN ('user_id','order_id','request_id','latency_ms','prompt_version');")
        print("ai_usage_log 新字段数：", cols)
        if cols.strip() == "5":
            print("==> 结构已迁移，只执行可重复的数据部分")
            sftp.put(DATA, "/tmp/ai_p0_data.sql")
            print(run(cli, MYSQL + " < /tmp/ai_p0_data.sql").strip() or "(data sql ok)")
        else:
            print("==> 执行完整迁移（ALTER + 权限 + 种子）")
            sftp.put(MIGRATION, "/tmp/ai_p0_migration.sql")
            out = run(cli, MYSQL + " < /tmp/ai_p0_migration.sql", timeout=120)
            print(out.strip() or "(migration ok)")

        # 回读
        print("permission:", sql(cli, "SELECT id,perm_key,perm_type FROM sys_permission WHERE perm_key='ai:diagnose';"))
        print("grant role4:", sql(cli, "SELECT COUNT(*) FROM sys_role_permission srp JOIN sys_permission p "
                                      "ON p.id=srp.permission_id WHERE srp.role_id=4 AND p.perm_key='ai:diagnose';"))
        print("tenant7 articles:", sql(cli, "SELECT COUNT(*) FROM fault_article WHERE tenant_id=7 AND status=1 AND del_flag=0;"))
        print("columns:", sql(cli, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='netsight' "
                                   "AND table_name='ai_usage_log' AND column_name IN "
                                   "('user_id','order_id','request_id','latency_ms','prompt_version');"))

        # 更新 nams.env
        old = run(cli, "cat %s" % ENV_PATH)
        keys = ["AI_MASK_SENSITIVE", "AI_CONNECT_TIMEOUT_MS", "AI_READ_TIMEOUT_MS",
                "AI_MAX_RETRIES", "AI_MAX_CONCURRENT", "AI_FAILURE_THRESHOLD",
                "AI_OPEN_SECONDS", "AI_DAILY_CALL_LIMIT"]
        values = {
            "AI_MASK_SENSITIVE": "true",
            "AI_CONNECT_TIMEOUT_MS": "3000",
            "AI_READ_TIMEOUT_MS": "15000",
            "AI_MAX_RETRIES": "0",
            "AI_MAX_CONCURRENT": "10",
            "AI_FAILURE_THRESHOLD": "5",
            "AI_OPEN_SECONDS": "60",
            "AI_DAILY_CALL_LIMIT": "200",
        }
        lines = []
        for line in old.splitlines():
            if any(line.startswith(k + "=") for k in keys):
                continue
            lines.append(line)
        if lines and lines[-1].strip():
            lines.append("")
        lines.append("# ==== AI P0 (added %s) ====" % STAMP)
        for k in keys:
            lines.append("%s=%s" % (k, values[k]))
        new_content = "\n".join(lines) + "\n"
        local_tmp = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "nams.env.new")
        with open(local_tmp, "w", encoding="utf-8", newline="\n") as f:
            f.write(new_content)
        run(cli, "cp -a %s %s.bak-%s" % (ENV_PATH, ENV_PATH, STAMP))
        sftp.put(local_tmp, "/tmp/nams.env.new")
        run(cli, "mv /tmp/nams.env.new %s && chmod 600 %s" % (ENV_PATH, ENV_PATH))
        print("==> nams.env 已更新，备份：%s.bak-%s" % (ENV_PATH, STAMP))
        print(run(cli, "grep -E '^(AI_MASK_SENSITIVE|AI_CONNECT_TIMEOUT_MS|AI_READ_TIMEOUT_MS|"
                       "AI_MAX_RETRIES|AI_MAX_CONCURRENT|AI_FAILURE_THRESHOLD|AI_OPEN_SECONDS|"
                       "AI_DAILY_CALL_LIMIT)=' %s" % ENV_PATH))
    finally:
        sftp.close()
        cli.close()


if __name__ == "__main__":
    main()
