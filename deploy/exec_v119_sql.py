import paramiko, sys

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"
LOCAL_SQL = r"E:\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.1.19.sql"
REMOTE_SQL = "/tmp/netsight-upgrade-v1.1.19.sql"

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

# 校验表与权限
for q in [
    "SHOW TABLES LIKE 'notify_contact';",
    "SELECT id,perm_name,perm_key,parent_id FROM sys_permission WHERE id BETWEEN 135 AND 139 ORDER BY id;",
    "SELECT role_id,COUNT(*) cnt FROM sys_role_permission WHERE permission_id BETWEEN 135 AND 139 GROUP BY role_id ORDER BY role_id;",
]:
    _, so, _ = ssh.exec_command('mysql --default-character-set=utf8mb4 -usadmin -pChinaunicom@1358 netsight -e "%s" 2>&1' % q)
    print("=== Q:", q)
    print(so.read().decode("utf-8", "replace"))

ssh.close()
print("DONE")
