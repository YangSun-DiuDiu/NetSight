# -*- coding: utf-8 -*-
import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)
sql = """
SET SESSION group_concat_max_len=100000;
SELECT GROUP_CONCAT(p.perm_key ORDER BY p.perm_key SEPARATOR ',') FROM netsight.sys_role r
LEFT JOIN netsight.sys_role_permission rp ON rp.role_id=r.id
LEFT JOIN netsight.sys_permission p ON p.id=rp.permission_id AND p.del_flag=0
WHERE r.id=2 AND p.perm_type='button'
"""
cmd = "mysql -usadmin -p'Chinaunicom@1358' --default-character-set=utf8mb4 -N -e \"" + sql.replace('"', '\\"') + "\""
_, out, err = ssh.exec_command(cmd, timeout=15)
print("role2 button perms:\n", out.read().decode("utf-8","ignore"))
print("ERR:\n", err.read().decode("utf-8","ignore")[:300])
ssh.close()
