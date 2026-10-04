#!/usr/bin/env python3
"""部署 NetSight H5 移动端工单功能到 192.168.1.55"""
import paramiko
import os
import sys

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"

def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=10)

    # 1. 执行数据库升级 SQL
    print("=== 1. 执行数据库升级 ===")
    sftp = ssh.open_sftp()
    sql_local = r"E:\gitee\NetSight1.0\netsight-server\sql\netsight-upgrade-v1.3.0.sql"
    sql_remote = "/tmp/netsight-upgrade-v1.3.0.sql"
    sftp.put(sql_local, sql_remote)
    sftp.close()
    stdin, stdout, stderr = ssh.exec_command(
        f"mysql -u sadmin -pChinaunicom@1358 netsight < {sql_remote} 2>&1"
    )
    out = stdout.read().decode()
    err = stderr.read().decode()
    print(out or err or "数据库升级完成")

    # 2. 创建上传目录
    print("\n=== 2. 创建上传目录 ===")
    stdin, stdout, stderr = ssh.exec_command("mkdir -p /opt/nams-server/uploads && echo OK")
    print(stdout.read().decode().strip())

    # 3. 部署后端 jar
    print("\n=== 3. 部署后端 jar ===")
    jar_local = r"E:\gitee\NetSight1.0\netsight-server\target\netsight-server.jar"
    jar_remote = "/opt/nams-server/netsight-server.jar"
    sftp = ssh.open_sftp()
    sftp.put(jar_local, jar_remote)
    sftp.close()
    print("jar 上传完成")

    # 重启服务
    print("重启 netsight-server...")
    stdin, stdout, stderr = ssh.exec_command("systemctl restart netsight-server && sleep 3 && systemctl is-active netsight-server")
    print(stdout.read().decode().strip())

    # 健康检查
    stdin, stdout, stderr = ssh.exec_command("curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health")
    print("health:", stdout.read().decode().strip())

    # 4. 打包并部署前端
    print("\n=== 4. 部署前端 ===")
    # 先在本地打包
    import subprocess
    print("打包前端 dist...")
    ret = subprocess.run(
        ["tar", "-czf", "dist.tar.gz", "dist"],
        cwd=r"E:\gitee\NetSight1.0\netsight-ui",
        capture_output=True, text=True
    )
    if ret.returncode != 0:
        # Windows tar
        ret = subprocess.run(
            [r"C:\Windows\System32\tar.exe", "-czf", "dist.tar.gz", "dist"],
            cwd=r"E:\gitee\NetSight1.0\netsight-ui",
            capture_output=True, text=True
        )
    print("tar:", ret.returncode)

    sftp = ssh.open_sftp()
    sftp.put(r"E:\gitee\NetSight1.0\netsight-ui\dist.tar.gz", "/tmp/dist.tar.gz")
    sftp.close()
    print("前端包上传完成")

    stdin, stdout, stderr = ssh.exec_command(
        "cd /opt/nams-ui && tar -xzf /tmp/dist.tar.gz && echo OK"
    )
    print(stdout.read().decode().strip())

    # 5. Nginx 配置：确认 /m/ 和 /uploads/ 路由
    print("\n=== 5. 检查 Nginx 配置 ===")
    stdin, stdout, stderr = ssh.exec_command("cat /etc/nginx/conf.d/nams.conf")
    conf = stdout.read().decode()
    needs_reload = False
    # 确保 /uploads/ 代理到后端
    if "/uploads/" not in conf:
        print("添加 /uploads/ 代理...")
        conf = conf.replace(
            "location /prod-api/",
            "location /uploads/ {\n                proxy_pass http://localhost:8080;\n                proxy_set_header Host $host;\n            }\n\n            location /prod-api/"
        )
        sftp = ssh.open_sftp()
        with sftp.open("/etc/nginx/conf.d/nams.conf", "w") as f:
            f.write(conf)
        sftp.close()
        needs_reload = True

    # 确认 /m/ 路由 history fallback（SPA 默认 try_files 已覆盖）
    if needs_reload:
        stdin, stdout, stderr = ssh.exec_command("nginx -t && nginx -s reload 2>&1")
        print(stdout.read().decode(), stderr.read().decode())
    else:
        print("Nginx 配置无需修改")

    # 6. 最终验证
    print("\n=== 6. 验证 ===")
    stdin, stdout, stderr = ssh.exec_command("curl -s -o /dev/null -w '%{http_code}' http://localhost/m/login")
    print("H5 登录页 /m/login:", stdout.read().decode().strip())

    stdin, stdout, stderr = ssh.exec_command("curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/m/order/my-list")
    print("移动端接口 /m/order/my-list (无token应401):", stdout.read().decode().strip())

    ssh.close()
    print("\n部署完成！")

if __name__ == "__main__":
    main()
