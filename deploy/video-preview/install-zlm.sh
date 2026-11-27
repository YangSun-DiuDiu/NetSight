#!/usr/bin/env bash
# 在边缘网关上安装/更新 ZLMediaKit 并生成本地视频预览配置。
# 前置：/opt/src/ZLMediaKit 已编译；模板与 service 文件已放到 /opt/zlmediakit/。
set -euo pipefail

ZLM_DIR=/opt/zlmediakit
TPL=/opt/zlmediakit/config.ini.template
SERVICE=/opt/zlmediakit/zlmediakit.service

mkdir -p "$ZLM_DIR"/{lib,www,logs}

SRC_BIN=$(find /opt/src/ZLMediaKit/release -type f -name MediaServer | head -n 1)
if [ -z "$SRC_BIN" ]; then
  echo "ERROR: MediaServer not found under /opt/src/ZLMediaKit/release" >&2
  exit 1
fi
SRC_DIR=$(dirname "$SRC_BIN")
cp -f "$SRC_BIN" "$ZLM_DIR/MediaServer"
chmod +x "$ZLM_DIR/MediaServer"
find "$SRC_DIR" -maxdepth 1 -type f -name '*.so*' -exec cp -f {} "$ZLM_DIR/lib/" \;
echo "installed binary: $SRC_BIN"

SECRET=$(openssl rand -hex 24)
MEDIA_ID="nams-$(hostname)-$(openssl rand -hex 4)"
sed -e "s/__ZLM_SECRET__/${SECRET}/g" \
    -e "s/__MEDIA_SERVER_ID__/${MEDIA_ID}/g" \
    "$TPL" > "$ZLM_DIR/config.ini"
chmod 600 "$ZLM_DIR/config.ini"

# nams-agent 读取的媒体面配置
python3 - "$SECRET" <<'PY'
import json, sys, os
p = "/opt/nams-gateway/conf/video-settings.json"
data = {
    "enabled": True,
    "zlmApiBase": "http://127.0.0.1:8000",
    "zlmSecret": sys.argv[1],
    "maxSessions": 8,
    "startTimeoutMs": 5000,
    "publicHttpPort": 8000,
    "noneReaderDelaySec": 60,
}
os.makedirs(os.path.dirname(p), exist_ok=True)
with open(p, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
os.chmod(p, 0o600)
if not os.path.exists("/opt/nams-gateway/conf/video-endpoints.json"):
    with open("/opt/nams-gateway/conf/video-endpoints.json", "w", encoding="utf-8") as f:
        json.dump({"version": 1, "updatedAt": "", "endpoints": []}, f, ensure_ascii=False, indent=2)
    os.chmod("/opt/nams-gateway/conf/video-endpoints.json", 0o600)
PY

cp -f "$SERVICE" /etc/systemd/system/zlmediakit.service
systemctl daemon-reload
systemctl enable zlmediakit >/dev/null 2>&1 || true
systemctl restart zlmediakit
systemctl restart nams-agent

sleep 2
systemctl --no-pager --full status zlmediakit | head -n 20
ss -lntp | grep -E ':8000|:8081' || true
curl -sS "http://127.0.0.1:8000/index/api/getServerConfig?secret=${SECRET}" | head -c 300 || true
echo
