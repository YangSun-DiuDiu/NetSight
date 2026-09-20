# -*- coding: utf-8 -*-
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

sql = """SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='netsight' AND ((TABLE_NAME='edge_gateway' AND COLUMN_NAME IN ('gateway_token','pushplus_token'))
OR (TABLE_NAME='sys_tenant' AND COLUMN_NAME IN ('webhook_token','pushplus_token')));"""
o, e = run("mysql -usadmin -pChinaunicom@1358 netsight -e \"%s\"" % sql.replace('"', '\\"'))
print(o.strip() or e.strip())
client.close()
