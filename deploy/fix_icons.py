import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=10)

# 统一替换所有菜单图标为 SVG
sql = """
UPDATE sys_permission SET icon='message' WHERE icon='el-icon-message';
UPDATE sys_permission SET icon='date' WHERE icon='el-icon-date';
UPDATE sys_permission SET icon='education' WHERE icon='el-icon-reading';
UPDATE sys_permission SET icon='bug' WHERE icon='el-icon-bell';
UPDATE sys_permission SET icon='clipboard' WHERE icon='el-icon-s-order';
UPDATE sys_permission SET icon='build' WHERE icon='el-icon-tickets';
UPDATE sys_permission SET icon='shopping' WHERE icon='el-icon-box';
UPDATE sys_permission SET icon='log' WHERE icon='el-icon-document';
UPDATE sys_permission SET icon='link' WHERE icon='el-icon-message' AND perm_name='渠道管理';
-- 设备资产用 component 区分于监控大屏 monitor
UPDATE sys_permission SET icon='component' WHERE id=40;
-- 系统管理用 tool
UPDATE sys_permission SET icon='tool' WHERE id=1;
-- 首页用 dashboard
UPDATE sys_permission SET icon='dashboard' WHERE path='/index' OR path='index';
"""

sftp = ssh.open_sftp()
with sftp.file('/tmp/fix_icons.sql', 'w') as f:
    f.write(sql)
sftp.close()

stdin, stdout, stderr = ssh.exec_command('mysql -u sadmin -pChinaunicom@1358 netsight < /tmp/fix_icons.sql 2>&1')
print(stdout.read().decode('utf-8', errors='replace'))
print(stderr.read().decode('utf-8', errors='replace'))

# 验证结果
stdin, stdout, stderr = ssh.exec_command("mysql -u sadmin -pChinaunicom@1358 netsight -e 'SELECT id,perm_name,icon FROM sys_permission WHERE perm_type=\"menu\" AND del_flag=0 ORDER BY parent_id,sort;'")
print(stdout.read().decode('utf-8', errors='replace'))
ssh.close()
