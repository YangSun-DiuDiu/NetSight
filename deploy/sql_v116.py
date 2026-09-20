# -*- coding: utf-8 -*-
"""NetSight V1.1.6 云端 SQL 升级（paramiko）：
上传并执行 netsight-upgrade-v1.1.6.sql（sys_tenant 加 webhook_token/webhook_token_time + 存量初始化 + 唯一索引）
目标：192.168.1.55
"""
import paramiko

HOST = "192.168.1.55"
SSH_USER = "root"
SSH_PWD = "Chinaunicom@1358"
MYSQL_USER = "sadmin"
MYSQL_PWD = "Chinaunicom@1358"
MYSQL_DB = "netsight"
SQL_LOCAL = r"E:\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.1.6.sql"

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())


def run(cmd, timeout=120):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


try:
    cli.connect(HOST, username=SSH_USER, password=SSH_PWD, timeout=15)
    print("==> 已连接", HOST)

    sftp = cli.open_sftp()
    sftp.put(SQL_LOCAL, "/tmp/netsight-upgrade-v1.1.6.sql")
    sftp.close()
    print("==> SQL 已上传 /tmp/netsight-upgrade-v1.1.6.sql")

    out = run("mysql -u%s -p'%s' %s < /tmp/netsight-upgrade-v1.1.6.sql 2>&1" % (MYSQL_USER, MYSQL_PWD, MYSQL_DB))
    print("==> 执行结果:", out if out else "(无输出=成功)")

    verify = run(
        "mysql -u%s -p'%s' %s -e \"SHOW COLUMNS FROM sys_tenant LIKE 'webhook_token'; "
        "SELECT id, tenant_name, LEFT(webhook_token,8) AS tok_prefix, CHAR_LENGTH(webhook_token) AS tok_len, "
        "webhook_token_time, status FROM sys_tenant WHERE del_flag=0;\" 2>&1"
        % (MYSQL_USER, MYSQL_PWD, MYSQL_DB))
    print("==> 验证:\n", verify)

finally:
    cli.close()
