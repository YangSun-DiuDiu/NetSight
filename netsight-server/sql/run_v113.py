# -*- coding: utf-8 -*-
"""执行 v1.1.3 增量 SQL 并验证（paramiko 连 192.168.1.55）"""
import paramiko

HOST, USER, PWD = '192.168.1.55', 'root', 'Chinaunicom@1358'
SQL_PATH = r'E:\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.1.3.sql'

sql = open(SQL_PATH, encoding='utf-8').read()
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, 22, USER, PWD, timeout=15)

sftp = ssh.open_sftp()
with sftp.open('/tmp/v113.sql', 'w') as f:
    f.write(sql)
sftp.close()

# 预处理：若存在旧唯一键 uk_template_code（tenant+code），删除后重建为 tenant+code+channel
pre = """
SET @old_key = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='netsight' AND table_name='notification_template' AND index_name='uk_template_code');
SET @new_key = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='netsight' AND table_name='notification_template' AND index_name='uk_template_code_channel');
SET @s = IF(@old_key > 0 AND @new_key = 0,
  'ALTER TABLE notification_template DROP INDEX uk_template_code, ADD UNIQUE KEY uk_template_code_channel (tenant_id, template_code, channel_type)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
"""
stdin_p, stdout_p, stderr_p = ssh.exec_command(
    'mysql -usadmin -pChinaunicom@1358 netsight -e "' + pre.replace('"', '\\"') + '" 2>&1', timeout=60)
print('PRE_OUT:', stdout_p.read().decode('utf-8', 'ignore')[:1000])
print('PRE_ERR:', stderr_p.read().decode('utf-8', 'ignore')[:1000])

stdin, stdout, stderr = ssh.exec_command(
    'mysql -usadmin -pChinaunicom@1358 netsight < /tmp/v113.sql 2>&1', timeout=120)
out = stdout.read().decode('utf-8', 'ignore')
err = stderr.read().decode('utf-8', 'ignore')
print('STDOUT:', out[:3000])
print('STDERR:', err[:3000])

verify = """
SELECT 'event_record' AS tbl, COUNT(*) AS cnt FROM event_record
UNION ALL SELECT 'notification_rule', COUNT(*) FROM notification_rule
UNION ALL SELECT 'notification_template', COUNT(*) FROM notification_template
UNION ALL SELECT 'notification_log', COUNT(*) FROM notification_log;
SELECT COUNT(*) AS perms_60_75 FROM sys_permission WHERE id BETWEEN 60 AND 75;
SELECT COUNT(*) AS bindings FROM sys_role_permission WHERE role_id=1 AND permission_id BETWEEN 60 AND 75;
"""
stdin2, stdout2, stderr2 = ssh.exec_command(
    'mysql -usadmin -pChinaunicom@1358 netsight -e "' + verify.replace('"', '\\"') + '"', timeout=60)
print('VERIFY_OUT:', stdout2.read().decode('utf-8', 'ignore')[:2000])
print('VERIFY_ERR:', stderr2.read().decode('utf-8', 'ignore')[:1000])
ssh.close()
print('DONE')
