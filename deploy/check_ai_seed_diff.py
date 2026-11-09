# -*- coding: utf-8 -*-
"""只读比对租户 1 与租户 7 的 fault_article 标题差异。"""
import json
import shlex

import paramiko

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
MYSQL = "mysql -usadmin -p'Chinaunicom@1358' netsight -N -B -e"


def sql(cli, query):
    cmd = "%s %s 2>/dev/null" % (MYSQL, shlex.quote(query))
    _, out, _ = cli.exec_command(cmd, timeout=60)
    return out.read().decode("utf-8", "ignore").strip()


def main():
    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)
    try:
        print("tenant1 count:", sql(cli, "SELECT COUNT(*) FROM fault_article WHERE tenant_id=1 AND status=1 AND del_flag=0;"))
        print("tenant7 count:", sql(cli, "SELECT COUNT(*) FROM fault_article WHERE tenant_id=7 AND status=1 AND del_flag=0;"))
        print("missing in 7:")
        print(sql(cli, "SELECT a.id,a.title FROM fault_article a WHERE a.tenant_id=1 AND a.status=1 AND a.del_flag=0 "
                       "AND NOT EXISTS (SELECT 1 FROM fault_article b WHERE b.tenant_id=7 AND b.title=a.title "
                       "AND b.status=1 AND b.del_flag=0) ORDER BY a.id;"))
        print("dup titles in 1:")
        print(sql(cli, "SELECT title,COUNT(*) c FROM fault_article WHERE tenant_id=1 AND status=1 AND del_flag=0 "
                       "GROUP BY title HAVING c>1;"))
    finally:
        cli.close()


if __name__ == "__main__":
    main()
