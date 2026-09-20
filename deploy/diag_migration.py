# -*- coding: utf-8 -*-
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

# 1. 查服务日志中的迁移记录与启动异常
o, e = run("journalctl -u netsight-server --since '16:20' --no-pager | head -120")
print(o[-4000:] or e[:500])
print("====")
# 2. 确认 jar 内是否包含 TokenCryptoMigration 类
o, e = run("unzip -l /opt/nams-server/netsight-server.jar | grep -E 'TokenCrypto|TraceIdFilter'")
print(o or e)
client.close()
