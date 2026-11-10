#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NetSight 服务号全面支持：115 测试环境部署脚本（可重复执行）

用法（PowerShell）：
  $env:NAMS_SSH_PASSWORD='...'
  python deploy/deploy_wechat_mp_115.py `
    --jar netsight-server/target/netsight-server.jar `
    --dist-dir netsight-ui/dist

安全约定：
  - 仅操作 115.159.194.149（测试环境），不触碰生产 1.94.26.126；
  - 部署前备份 jar、dist、数据库；
  - 数据库脚本先执行结构脚本，再执行数据脚本（均幂等）；
  - 后端重启严格使用 /opt/nams-server/start.sh。
"""
from __future__ import annotations

import argparse
import os
import posixpath
import shlex
import tarfile
import tempfile
import time
from datetime import datetime
from pathlib import Path

import paramiko

HOST = os.environ.get("NAMS_SSH_HOST", "115.159.194.149")
PORT = int(os.environ.get("NAMS_SSH_PORT", "22"))
USER = os.environ.get("NAMS_SSH_USER", "root")
PASSWORD = os.environ.get("NAMS_SSH_PASSWORD") or os.environ.get("SSH_PASSWORD")

REMOTE_SERVER_DIR = "/opt/nams-server"
REMOTE_JAR = REMOTE_SERVER_DIR + "/netsight-server.jar"
REMOTE_UI_DIR = "/opt/nams-ui"
REMOTE_DIST = REMOTE_UI_DIR + "/dist"
REMOTE_START = REMOTE_SERVER_DIR + "/start.sh"
REMOTE_ENV = REMOTE_SERVER_DIR + "/nams.env"


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


class Remote:
    def __init__(self) -> None:
        if not PASSWORD:
            raise SystemExit("缺少 NAMS_SSH_PASSWORD / SSH_PASSWORD 环境变量")
        self.client = paramiko.SSHClient()
        self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self.client.connect(HOST, PORT, USER, PASSWORD, timeout=20, banner_timeout=30, auth_timeout=30)
        self.sftp = self.client.open_sftp()

    def close(self) -> None:
        try:
            self.sftp.close()
        finally:
            self.client.close()

    def run(self, cmd: str, check: bool = True, timeout: int = 300) -> tuple[int, str, str]:
        stdin, stdout, stderr = self.client.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        code = stdout.channel.recv_exit_status()
        if out.strip():
            log(out.strip()[-3000:])
        if err.strip():
            log("stderr: " + err.strip()[-3000:])
        if check and code != 0:
            raise RuntimeError(f"远程命令失败({code}): {cmd}")
        return code, out, err

    def upload(self, local: str, remote: str) -> None:
        size = os.path.getsize(local)
        log(f"上传 {local} -> {remote} ({size / 1024 / 1024:.1f} MB)")
        self.sftp.put(local, remote)
        remote_size = self.sftp.stat(remote).st_size
        if remote_size != size:
            raise RuntimeError(f"上传大小不一致: {local} {size} != {remote_size}")
        log("上传完成")


def build_dist_tar(dist_dir: Path) -> Path:
    if not dist_dir.is_dir():
        raise SystemExit(f"前端产物目录不存在: {dist_dir}")
    tmp = Path(tempfile.gettempdir()) / f"netsight-dist-wechat-mp-{datetime.now():%Y%m%d%H%M%S}.tar.gz"
    with tarfile.open(tmp, "w:gz") as tar:
        tar.add(dist_dir, arcname="dist")
    log(f"前端打包 {tmp} ({tmp.stat().st_size / 1024 / 1024:.1f} MB)")
    return tmp


def apply_sql(remote: Remote, sql_files: list[tuple[str, Path]], stamp: str) -> None:
    for name, local in sql_files:
        remote_path = f"/tmp/{name}"
        remote.upload(str(local), remote_path)

    # 备份数据库（只备份，不改动任何数据）
    backup_sql = f"{REMOTE_SERVER_DIR}/wechat-mp-backup-{stamp}.sql"
    remote.run(
        "set -e; set -a; . " + REMOTE_ENV + "; set +a; "
        f"MYSQL_PWD=\"${{MYSQL_PASSWORD:-$MYSQL_PASS}}\" mysqldump "
        f"-h \"${{MYSQL_HOST:-127.0.0.1}}\" -P \"${{MYSQL_PORT:-3306}}\" "
        f"-u \"${{MYSQL_USER:-root}}\" --single-transaction --quick --routines "
        f"\"${{MYSQL_DB:-netsight}}\" > {shlex.quote(backup_sql)}; "
        f"ls -lh {shlex.quote(backup_sql)}",
        timeout=900,
    )

    for name, _ in sql_files:
        log(f"执行 SQL: {name}")
        remote.run(
            "set -e; set -a; . " + REMOTE_ENV + "; set +a; "
            f"MYSQL_PWD=\"${{MYSQL_PASSWORD:-$MYSQL_PASS}}\" mysql "
            f"-h \"${{MYSQL_HOST:-127.0.0.1}}\" -P \"${{MYSQL_PORT:-3306}}\" "
            f"-u \"${{MYSQL_USER:-root}}\" \"${{MYSQL_DB:-netsight}}\" "
            f"< /tmp/{name}",
            timeout=300,
        )
    log("SQL 执行完成")


def restart_backend(remote: Remote, jar_local: Path, stamp: str) -> None:
    remote.upload(str(jar_local), REMOTE_JAR + ".new")
    remote.run(
        "set -e; "
        f"cp -p {shlex.quote(REMOTE_JAR)} {shlex.quote(REMOTE_JAR + '.bak-wechat-mp-' + stamp)}; "
        f"if [ -f {REMOTE_SERVER_DIR}/app.pid ]; then "
        f"OLD=$(cat {REMOTE_SERVER_DIR}/app.pid); kill $OLD 2>/dev/null || true; "
        f"for i in $(seq 1 30); do kill -0 $OLD 2>/dev/null || break; sleep 1; done; fi; "
        f"mv {REMOTE_JAR}.new {REMOTE_JAR}; "
        f"chmod 644 {REMOTE_JAR}; "
        f"cd {REMOTE_SERVER_DIR} && ./start.sh",
        timeout=300,
    )

    # 健康检查：最多等待 120 秒
    deadline = time.time() + 120
    while time.time() < deadline:
        code, out, _ = remote.run(
            "curl -s -o /tmp/health.out -w '%{http_code}' http://127.0.0.1:8080/actuator/health || true",
            check=False,
            timeout=30,
        )
        status = out.strip()
        if status == "200":
            remote.run("cat /tmp/health.out", check=False)
            log("后端健康检查 200")
            return
        time.sleep(3)
    remote.run(f"tail -n 120 {REMOTE_SERVER_DIR}/app.log", check=False)
    raise RuntimeError("后端健康检查超时（120s）")


def deploy_frontend(remote: Remote, dist_tar: Path, stamp: str) -> None:
    remote.upload(str(dist_tar), "/tmp/netsight-dist.tar.gz")
    remote.run(
        "set -e; "
        f"if [ -d {REMOTE_DIST} ]; then mv {REMOTE_DIST} {REMOTE_UI_DIR}/dist.bak-wechat-mp-{stamp}; fi; "
        f"mkdir -p {REMOTE_UI_DIR}; "
        f"tar -xzf /tmp/netsight-dist.tar.gz -C {REMOTE_UI_DIR}; "
        f"test -f {REMOTE_DIST}/index.html; "
        f"chmod -R a+rX {REMOTE_DIST}; "
        f"ls -ld {REMOTE_DIST}; du -sh {REMOTE_DIST}",
        timeout=300,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default="netsight-server/target/netsight-server.jar")
    parser.add_argument("--dist-dir", default="netsight-ui/dist")
    parser.add_argument("--migration", default="docs/sql/wechat_mp_full_support_migration.sql")
    parser.add_argument("--data", default="docs/sql/wechat_mp_full_support_data.sql")
    parser.add_argument("--skip-sql", action="store_true")
    parser.add_argument("--skip-backend", action="store_true")
    parser.add_argument("--skip-frontend", action="store_true")
    args = parser.parse_args()

    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise SystemExit(f"jar 不存在: {jar}")
    migration = Path(args.migration).resolve()
    data = Path(args.data).resolve()
    if not migration.is_file() or not data.is_file():
        raise SystemExit("SQL 脚本不存在")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    remote = Remote()
    try:
        remote.run("hostname; date '+%F %T'; test -f " + REMOTE_START, timeout=60)
        if not args.skip_sql:
            apply_sql(remote, [("wechat_mp_full_support_migration.sql", migration),
                               ("wechat_mp_full_support_data.sql", data)], stamp)
        if not args.skip_backend:
            restart_backend(remote, jar, stamp)
        if not args.skip_frontend:
            deploy_frontend(remote, build_dist_tar(Path(args.dist_dir).resolve()), stamp)
        log(f"部署完成，备份时间戳 {stamp}")
    finally:
        remote.close()


if __name__ == "__main__":
    main()
