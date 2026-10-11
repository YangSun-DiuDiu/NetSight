# -*- coding: utf-8 -*-
"""点检巡检修复 V1.1.21：执行增量 SQL（item_ids / cycle_day）到 192.168.1.55"""
import paramiko

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"
LOCAL_SQL = r"E:\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.1.21.sql"
REMOTE_SQL = "/tmp/netsight-upgrade-v1.1.21.sql"

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username=USER, password=PWD, timeout=15)

sftp = ssh.open_sftp()
sftp.put(LOCAL_SQL, REMOTE_SQL)
sftp.close()

cmd = (
    'mysql --default-character-set=utf8mb4 -usadmin -pChinaunicom@1358 netsight < '
    + REMOTE_SQL + ' 2>&1'
)
stdin, stdout, stderr = ssh.exec_command(cmd)
out = stdout.read().decode("utf-8", "replace")
err = stderr.read().decode("utf-8", "replace")
print("=== EXEC OUTPUT ===")
print(out)
if err.strip():
    print("=== STDERR ===")
    print(err)

# 校验新列
for q in [
    "SHOW COLUMNS FROM inspection_plan LIKE 'item_ids';",
    "SHOW COLUMNS FROM inspection_plan LIKE 'cycle_day';",
    "SHOW COLUMNS FROM inspection_task LIKE 'item_ids';",
]:
    _, so, _ = ssh.exec_command('mysql --default-character-set=utf8mb4 -usadmin -pChinaunicom@1358 netsight -e "%s" 2>&1' % q)
    print("=== Q:", q)
    print(so.read().decode("utf-8", "replace"))

ssh.close()
print("DONE")
