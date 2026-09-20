# -*- coding: utf-8 -*-
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd):
    _, out, err = client.exec_command(cmd, timeout=30)
    return out.read().decode("utf-8", "ignore"), err.read().decode("utf-8", "ignore")

o, e = run("ls -la /opt/nams-server/logs/ 2>/dev/null; tail -c 8000 /opt/nams-server/logs/nams-server.log 2>/dev/null || tail -c 8000 /opt/nams-server/logs/*.log 2>/dev/null")
print(o[-8000:] or e)
print("====")
# jar 内类检查（python zipfile）
o, e = run("python3 -c \"import zipfile;z=zipfile.ZipFile('/opt/nams-server/netsight-server.jar');print([n for n in z.namelist() if 'TokenCrypto' in n or 'TraceIdFilter' in n])\"")
print(o or e)
client.close()
