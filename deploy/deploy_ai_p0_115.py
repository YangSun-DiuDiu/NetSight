# -*- coding: utf-8 -*-
"""部署 AI P0 改造到测试环境 115.159.194.149（后端 jar + 前端 dist）。"""
import hashlib
import json
import os
import tarfile
import time
from datetime import datetime

import paramiko

CFG = r"C:\Users\Administrator\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.user_skills\java-app-deploy\scripts\deploy.config.json"
WT = r"D:\gitee\worktrees\NetSight1.0-repairer-user-account-binding"
JAR = os.path.join(WT, "netsight-server", "target", "netsight-server.jar")
DIST = os.path.join(WT, "netsight-ui", "dist")
STAMP = datetime.now().strftime("%Y%m%d-%H%M%S")
TAR = os.path.join(WT, "netsight-ui", "dist-ai-p0-%s.tar.gz" % STAMP)
REMOTE_JAR = "/opt/nams-server/netsight-server.jar"
REMOTE_JAR_NEW = "/opt/nams-server/netsight-server.jar.new"
REMOTE_TAR = "/tmp/nams-ui-dist-ai-p0-%s.tar.gz" % STAMP
REMOTE_DIST = "/opt/nams-ui/dist"
REMOTE_DIST_BAK = "/opt/nams-ui/dist.bak-ai-p0-%s" % STAMP
REMOTE_JAR_BAK = "/opt/nams-server/netsight-server.jar.bak-ai-p0-%s" % STAMP


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cli, cmd, timeout=90):
    _, out, err = cli.exec_command(cmd, timeout=timeout)
    return (out.read().decode("utf-8", "ignore") + err.read().decode("utf-8", "ignore")).strip()


def main():
    for p in (JAR, DIST):
        if not os.path.exists(p):
            raise SystemExit("missing: " + p)
    if not os.path.exists(TAR):
        print("==> 打包前端 dist ->", TAR)
        with tarfile.open(TAR, "w:gz") as tar:
            tar.add(DIST, arcname="dist")

    local_jar_md5 = md5(JAR)
    local_tar_md5 = md5(TAR)
    print("local jar: %s bytes md5=%s" % (os.path.getsize(JAR), local_jar_md5))
    print("local tar: %s bytes md5=%s" % (os.path.getsize(TAR), local_tar_md5))

    with open(CFG, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(cfg["host"], port=cfg.get("port", 22), username=cfg["user"],
                password=cfg["password"], timeout=15)
    sftp = cli.open_sftp()
    try:
        print("==> 上传 jar / 前端 tar")
        sftp.put(JAR, REMOTE_JAR_NEW, confirm=True)
        sftp.put(TAR, REMOTE_TAR, confirm=True)
    finally:
        sftp.close()

    remote_jar_md5 = run(cli, "md5sum %s | awk '{print $1}'" % REMOTE_JAR_NEW).strip()
    remote_tar_md5 = run(cli, "md5sum %s | awk '{print $1}'" % REMOTE_TAR).strip()
    print("remote jar md5:", remote_jar_md5)
    print("remote tar md5:", remote_tar_md5)
    if remote_jar_md5 != local_jar_md5:
        raise SystemExit("jar upload md5 mismatch")
    if remote_tar_md5 != local_tar_md5:
        raise SystemExit("tar upload md5 mismatch")

    print("==> 备份旧 jar / 旧前端")
    print(run(cli, "cp -a %s %s && cp -a %s %s && ls -ld %s %s" % (
        REMOTE_JAR, REMOTE_JAR_BAK, REMOTE_DIST, REMOTE_DIST_BAK,
        REMOTE_JAR_BAK, REMOTE_DIST_BAK)).strip())

    print("==> 停止旧进程")
    pid = run(cli, "cat /opt/nams-server/app.pid 2>/dev/null || true").strip()
    if pid:
        run(cli, "kill %s 2>/dev/null || true" % pid)
    for _ in range(20):
        alive = run(cli, "kill -0 %s 2>/dev/null && echo alive || echo dead" % pid).strip() if pid else "dead"
        if alive == "dead":
            break
        time.sleep(1)
    if pid and alive != "dead":
        print("==> 强制停止", pid)
        run(cli, "kill -9 %s 2>/dev/null || true" % pid)
        time.sleep(2)

    print("==> 替换 jar 并启动")
    run(cli, "mv -f %s %s && chmod 644 %s" % (REMOTE_JAR_NEW, REMOTE_JAR, REMOTE_JAR))
    print(run(cli, "bash /opt/nams-server/start.sh && sleep 1 && cat /opt/nams-server/app.pid").strip())

    healthy = False
    for i in range(40):
        time.sleep(3)
        code = run(cli, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000").strip()
        print("  try %d: HTTP %s" % (i + 1, code))
        if code == "200":
            healthy = True
            break
    if not healthy:
        print("!! 后端未通过健康检查，日志尾部：")
        print(run(cli, "tail -100 /opt/nams-server/app.log 2>&1").strip())
        raise SystemExit(1)

    print("==> 替换前端 dist")
    print(run(cli, "rm -rf %s && mkdir -p %s && "
                   "tar -xzf %s -C /opt/nams-ui && chown -R root:root %s && chmod -R 755 %s" % (
                       REMOTE_DIST, REMOTE_DIST, REMOTE_TAR, REMOTE_DIST, REMOTE_DIST)).strip())

    print("==> 回读")
    print(run(cli, "jar tf %s | grep -E 'modules/ai/controller/AiController.class' || true" % REMOTE_JAR).strip())
    print(run(cli, "grep -R -l 'AI 建议仅供参考' /opt/nams-ui/dist/static/js 2>/dev/null | wc -l").strip())
    print(run(cli, "curl -s -o /dev/null -w 'health=%{http_code}\\n' http://127.0.0.1:8080/actuator/health; "
                   "ps -ef | grep netsight-server.jar | grep -v grep; ls -l --full-time " + REMOTE_JAR).strip())
    print("==> 完成，备份：%s / %s" % (REMOTE_JAR_BAK, REMOTE_DIST_BAK))
    cli.close()


if __name__ == "__main__":
    main()
