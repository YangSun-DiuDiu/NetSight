# -*- coding: utf-8 -*-
"""复验前端部署：index.html mtime、代理、showDevHint 生产隐藏"""
import paramiko

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("192.168.1.55", username="root", password="Chinaunicom@1358", timeout=10)

def run(cmd, t=20):
    _, out, err = client.exec_command(cmd, timeout=t)
    try:
        return out.read().decode("utf-8", "ignore").strip()
    except Exception:
        return "READ_TIMEOUT"

print("index.html mtime:", run("stat -c '%y' /opt/nams-ui/dist/index.html"))
print("首页:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/"))
print("prod-api:", run("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/prod-api/actuator/health"))
print("edge(网关鉴权-期望401):", run("curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1/edge/report/heartbeat -H 'Content-Type: application/json' -d '{}'"))
print("alert(期望401):", run("curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1/alert/push -H 'Content-Type: application/json' -d '{}'"))
# showDevHint: 生产应被替换为 false（VUE_APP_SHOW_DEV_HINT=false 编译期注入）
print("showDevHint(false) 存在:", run("grep -rl 'VUE_APP_SHOW_DEV_HINT' /opt/nams-ui/dist/static/js/ 2>/dev/null | head -1") != "" or run("grep -rl 'showDevHint' /opt/nams-ui/dist/static/js/ 2>/dev/null | head -1"))
print("login chunk 含 showDevHint:", run("grep -rl 'showDevHint' /opt/nams-ui/dist/static/js/ 2>/dev/null | head -3"))
client.close()
