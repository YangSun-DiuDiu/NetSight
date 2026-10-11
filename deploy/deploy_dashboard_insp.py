# -*- coding: utf-8 -*-
"""驾驶舱点检柱状图改动部署：前端 dist + 后端 jar → 192.168.1.55"""
import io
import os
import tarfile
import paramiko

HOST = "192.168.1.55"
USER = "root"
PWD = "Chinaunicom@1358"

JAR_SRC = r"E:\gitee\NetSight1.0\netsight-server\target\netsight-server.jar"
DIST_SRC = r"E:\gitee\NetSight1.0\netsight-ui\dist"
JAR_DST = "/opt/nams-server/netsight-server.jar"
DIST_DST = "/opt/nams-ui/dist"


def make_dist_tgz():
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        for root, dirs, files in os.walk(DIST_SRC):
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, DIST_SRC).replace("\\", "/")
                tf.add(full, arcname=arc)
    return buf.getvalue()


def main():
    dist_data = make_dist_tgz()
    print("dist tgz size:", len(dist_data))
    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(HOST, username=USER, password=PWD, timeout=20)
    sftp = cli.open_sftp()

    # 1. 上传前端
    with sftp.open("/tmp/nams-ui-dist.tar.gz", "wb") as fp:
        fp.write(dist_data)
    # 2. 上传后端
    sftp.put(JAR_SRC, "/tmp/netsight-server.jar")
    print("uploaded jar + dist")

    # 3. 解压前端（保留旧文件不删除，覆盖同名）
    run(cli, "mkdir -p /opt/nams-ui/dist && tar xzf /tmp/nams-ui-dist.tar.gz -C /opt/nams-ui/dist && echo DIST_OK")

    # 4. 找到服务名并重启后端
    out = run(cli, "systemctl list-units --type=service --no-pager | grep -iE 'nams|netsight' | head -5")
    print("service probe:", out)
    svc = "nams-server"
    if "nams-server" not in out and "netsight" in out:
        svc = "netsight-server"
    if svc not in out:
        # 兜底：列出 /opt/nams-server 下 systemd 文件
        out2 = run(cli, "ls /etc/systemd/system/ | grep -iE 'nams|netsight'")
        print("systemd probe:", out2)
        svc = out2.strip().splitlines()[0].replace(".service", "") if out2.strip() else "nams-server"

    run(cli, "cp /tmp/netsight-server.jar /opt/nams-server/netsight-server.jar && echo JAR_COPIED")
    run(cli, "systemctl restart %s && echo RESTART_OK" % svc)
    print("service restarted:", svc)

    # 5. 健康检查
    import time
    time.sleep(8)
    h = run(cli, "systemctl is-active %s" % svc)
    print("service status:", h)
    sftp.close()
    cli.close()


def run(cli, cmd):
    stdin, stdout, stderr = cli.exec_command(cmd, timeout=120)
    err = stderr.read().decode("utf-8", "ignore")
    out = stdout.read().decode("utf-8", "ignore")
    return (out + err).strip()


if __name__ == "__main__":
    main()
