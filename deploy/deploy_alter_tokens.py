# -*- coding: utf-8 -*-
"""在 .55 执行 v1.1.17 扩列 SQL（写临时文件避免反引号被 bash 吞）"""
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=60)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

sql = """
ALTER TABLE `edge_gateway`
    MODIFY COLUMN `gateway_token` VARCHAR(128) NOT NULL COMMENT '接入Token（网关上报鉴权，AES-256-GCM密文）',
    MODIFY COLUMN `pushplus_token` VARCHAR(128) DEFAULT NULL COMMENT 'PushPlus推送Token（AES-256-GCM密文）';
ALTER TABLE `sys_tenant`
    MODIFY COLUMN `webhook_token` VARCHAR(128) DEFAULT NULL COMMENT '租户Webhook接入Token（AlertManager告警上报鉴权，AES-256-GCM密文）',
    MODIFY COLUMN `pushplus_token` VARCHAR(128) DEFAULT NULL COMMENT 'PushPlus推送Token（AES-256-GCM密文）';
"""
sftp = client.open_sftp()
with sftp.open("/tmp/nams_alter_v1117.sql", "w") as f:
    f.write(sql)
sftp.close()

o, e = run("mysql -usadmin -pChinaunicom@1358 netsight < /tmp/nams_alter_v1117.sql && echo ALTER_OK")
print(o.strip() or e.strip())

check = """SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='netsight' AND ((TABLE_NAME='edge_gateway' AND COLUMN_NAME IN ('gateway_token','pushplus_token')) OR (TABLE_NAME='sys_tenant' AND COLUMN_NAME IN ('webhook_token','pushplus_token')));"""
o, e = run("mysql -usadmin -pChinaunicom@1358 netsight -e \"%s\"" % check.replace('"', '\\"'))
print(o.strip() or e.strip())
client.close()
