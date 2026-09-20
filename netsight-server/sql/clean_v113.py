# -*- coding: utf-8 -*-
"""清理第4周联调测试数据（保留演示数据：事件1-3、日志1-4、内置规则/模板）"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=15)

sql = """
-- 清理测试规则（测试规则-外线）
DELETE FROM notification_rule WHERE rule_name = '测试规则-外线';
-- 清理测试事件（事件4-9：去重验证与手动测试重复数据）
DELETE FROM event_record WHERE id >= 4;
-- 清理测试发送日志（日志5-8：手动测试日志）
DELETE FROM notification_log WHERE id >= 5;
-- 验证
SELECT 'event' AS k, COUNT(*) AS v FROM event_record
UNION ALL SELECT 'log', COUNT(*) FROM notification_log
UNION ALL SELECT 'rule', COUNT(*) FROM notification_rule
UNION ALL SELECT 'template', COUNT(*) FROM notification_template;
"""
cmd = "mysql -usadmin -pChinaunicom@1358 netsight -e \"" + sql.replace('"', '\\"') + "\""
si, so, se = ssh.exec_command(cmd, timeout=30)
print(so.read().decode('utf-8', 'ignore'))
err = se.read().decode('utf-8', 'ignore')
if 'Warning' not in err:
    print('ERR:', err[:500])
ssh.close()
print('CLEAN DONE')
