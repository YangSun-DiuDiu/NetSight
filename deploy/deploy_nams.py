# -*- coding: utf-8 -*-
"""NetSight 云端部署（paramiko 版，等价 deploy_nams.sh）：
上传 jar → 写 env → 写 systemd unit → 启动 → 健康检查
目标：192.168.1.55（MySQL/Redis 同机 127.0.0.1）
"""
import paramiko
import time
import os

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"
JAR = r"E:\gitee\NetSight1.0\netsight-server\target\netsight-server.jar"
APP_DIR = "/opt/nams-server"
ENV_FILE = APP_DIR + "/nams.env"

ENV_CONTENT = """# NetSight 云端运行环境变量（敏感配置不落代码仓库）
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DB=netsight
MYSQL_USER=sadmin
MYSQL_PASSWORD=Chinaunicom@1358
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
JWT_SECRET=NetSightDevSecretKey2026ForLocalDevOnly!
WEBHOOK_TOKEN=NetSightWebhookToken2026
# Token 落库加密密钥（AES-256-GCM；生产应更换为独立强密钥，更换会导致历史密文不可解密）
AES_TOKEN_KEY=NetSightAesTokenKey2026DevOnly!
# 通知通道：PushPlus 生产真实发送（yml 默认 ${PUSHPLUS_MOCK:true}，环境变量覆盖为 false）
PUSHPLUS_MOCK=false
"""

UNIT = """[Unit]
Description=NetSight NAMS Server
After=network-online.target mysql.service redis-server.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory={app}
EnvironmentFile={env}
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms512m -Xmx1024m -jar {app}/netsight-server.jar
ExecStop=/bin/kill -s TERM $MAINPID
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=append:{app}/logs/stdout.log
StandardError=append:{app}/logs/stderr.log

[Install]
WantedBy=multi-user.target
""".format(app=APP_DIR, env=ENV_FILE)

cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    cli.connect(HOST, username=USER, password=PWD, timeout=15)
    print("==> 已连接", HOST)

    # 1. 目录 + 上传 jar
    cli.exec_command("mkdir -p %s/logs" % APP_DIR)
    sftp = cli.open_sftp()
    sftp.put(JAR, APP_DIR + "/netsight-server.jar")
    sftp.close()
    print("==> jar 上传完成")

    # 2. env
    sftp = cli.open_sftp()
    with sftp.open(ENV_FILE, "w") as f:
        f.write(ENV_CONTENT)
    sftp.close()
    cli.exec_command("chmod 600 " + ENV_FILE)
    print("==> env 写入完成")

    # 3. systemd unit
    sftp = cli.open_sftp()
    with sftp.open("/etc/systemd/system/netsight-server.service", "w") as f:
        f.write(UNIT)
    sftp.close()
    cli.exec_command("systemctl daemon-reload")
    print("==> systemd unit 写入完成")

    # 4. 启动
    cli.exec_command("systemctl enable netsight-server && systemctl restart netsight-server")
    print("==> 服务已启动，开始健康检查")

    # 5. 健康检查
    ok = False
    for i in range(20):
        time.sleep(3)
        _, out, err = cli.exec_command("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000")
        code = out.read().decode().strip()
        print("    第 %d 次探测: HTTP %s" % (i + 1, code))
        if code == "200":
            ok = True
            break
    if ok:
        print("==> 部署成功！NetSight 运行于 http://%s:8080" % HOST)
        _, out, _ = cli.exec_command("systemctl status netsight-server --no-pager -l | head -12")
        print(out.read().decode())
    else:
        print("==> 部署超时，请查看日志：tail -100 %s/logs/stderr.log" % APP_DIR)
        _, out, _ = cli.exec_command("tail -40 %s/logs/stderr.log" % APP_DIR)
        print(out.read().decode())
finally:
    cli.close()
