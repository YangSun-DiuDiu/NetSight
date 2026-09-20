# -*- coding: utf-8 -*-
"""部署后验证：代理 /edge/ /alert/ /prod-api + dist 新内容"""
import paramiko, sys

HOST = "192.168.1.55"; USER = "root"; PWD = "Chinaunicom@1358"

def run(ssh, cmd, timeout=20):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "ignore")
    err = stderr.read().decode("utf-8", "ignore")
    return (out + err).strip()

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, 22, USER, PWD, timeout=15)

    # 1) 三个代理
    print("== 代理验证 ==")
    for p in ["/prod-api/actuator/health", "/edge/", "/alert/"]:
        code = run(ssh, "curl -s -o /dev/null -w '%%{http_code}' --max-time 5 http://127.0.0.1" + p)
        print(p, "->", code)

    # 2) nams.conf 是否含 /edge/ 与 /alert/ location
    conf = run(ssh, "cat /etc/nginx/conf.d/nams.conf")
    print("== nams.conf location ==")
    for loc in ["/prod-api/", "/edge/", "/alert/", "/ws/"]:
        print(loc, "present:", ("location " + loc) in conf)

    # 3) dist 最新 index.html mtime + 新绑定关键词
    print("== dist 内容 ==")
    print("index.html mtime:", run(ssh, "stat -c '%y' /opt/nams-ui/dist/index.html"))
    # 在所有 js 中搜新权限 key（压缩后 key 字符串保留）
    hit = run(ssh, "grep -rl 'workorder:order:dispatch\\|alert:event:manual\\|spare:part:stock' /opt/nams-ui/dist/static/js/ | head -5")
    print("新绑定 chunk:", hit if hit else "NONE-FOUND")
    hit2 = run(ssh, "grep -rl 'super_admin' /opt/nams-ui/dist/static/js/ | head -3")
    print("super_admin 出现 chunk:", hit2 if hit2 else "NONE-FOUND")

    ssh.close()
    print("== DONE ==")

if __name__ == "__main__":
    main()
