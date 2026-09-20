#!/bin/bash
# ============================================================
# NetSight 云端部署脚本（systemd 方式，二进制运行，非 Docker）
# 目标机：192.168.1.55（云端/测试服务器，MySQL+Redis 同机）
# 用法：
#   bash deploy_nams.sh <jar文件> <目标IP> <SSH用户>
# 示例：
#   bash deploy_nams.sh netsight-server.jar 192.168.1.55 root
# 依赖：目标机已安装 JDK 21；本机可 ssh/scp（或使用 paramiko 上传后执行）
# ============================================================
set -e

JAR="${1:?请指定 jar 文件名}"
HOST="${2:-192.168.1.55}"
USER="${3:-root}"
APP_DIR="/opt/nams-server"
ENV_FILE="${APP_DIR}/nams.env"

echo "==> 1/5 上传 jar 到 ${HOST}:${APP_DIR}/"
ssh "${USER}@${HOST}" "mkdir -p ${APP_DIR} ${APP_DIR}/logs"
scp "${JAR}" "${USER}@${HOST}:${APP_DIR}/netsight-server.jar"

echo "==> 2/5 写入环境变量文件 ${ENV_FILE}"
ssh "${USER}@${HOST}" "cat > ${ENV_FILE} <<'EOF'
# NetSight 云端运行环境变量（敏感配置不落代码仓库）
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=netsight
MYSQL_USERNAME=sadmin
MYSQL_PASSWORD=Chinaunicom@1358
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
JWT_SECRET=NetSightDevSecretKey2026ForLocalDevOnly!
WEBHOOK_TOKEN=NetSightWebhookToken2026
SERVER_PORT=8080
EOF
chmod 600 ${ENV_FILE}"

echo "==> 3/5 写入 systemd 服务单元"
ssh "${USER}@${HOST}" "cat > /etc/systemd/system/netsight-server.service <<'EOF'
[Unit]
Description=NetSight NAMS Server
After=network-online.target mysql.service redis-server.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms512m -Xmx1024m -jar ${APP_DIR}/netsight-server.jar
ExecStop=/bin/kill -s TERM \$MAINPID
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=append:${APP_DIR}/logs/stdout.log
StandardError=append:${APP_DIR}/logs/stderr.log

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload"

echo "==> 4/5 启动服务"
ssh "${USER}@${HOST}" "systemctl enable netsight-server && systemctl restart netsight-server"

echo "==> 5/5 健康检查（最多等 60 秒）"
for i in $(seq 1 20); do
  sleep 3
  CODE=$(ssh "${USER}@${HOST}" "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health 2>/dev/null || echo 000")
  echo "    第 ${i} 次探测: HTTP ${CODE}"
  if [ "${CODE}" = "200" ]; then
    echo "==> 部署成功！NetSight 已运行：http://${HOST}:8080"
    ssh "${USER}@${HOST}" "systemctl status netsight-server --no-pager -l | head -10"
    exit 0
  fi
done
echo "==> 部署超时，请查看日志：ssh ${USER}@${HOST} 'tail -100 ${APP_DIR}/logs/stderr.log'"
exit 1
