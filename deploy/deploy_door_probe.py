# -*- coding: utf-8 -*-
"""门禁 SDK 真机探测：上传 SDK 部署包到 .60 并运行 DoorSdkProbe"""
import paramiko, os, stat, time

HOST = "192.168.1.60"
USER = "root"
PWD = "Chinaunicom@1358"
LOCAL = r"E:\gitee\NetSight1.0\sdk\dahua-netsdk\deploy60"
REMOTE = "/opt/door-sdk"

def sftp_put_dir(sftp, local, remote):
    for root, dirs, files in os.walk(local):
        rel = os.path.relpath(root, local)
        target = remote if rel == "." else remote + "/" + rel.replace("\\", "/")
        try:
            sftp.stat(target)
        except IOError:
            sftp.mkdir(target)
        for f in files:
            lp = os.path.join(root, f)
            rp = target + "/" + f
            sftp.put(lp, rp)
            print("  ↑ %s (%d B)" % (rp, os.path.getsize(lp)))

def main():
    t = paramiko.Transport((HOST, 22))
    t.connect(username=USER, password=PWD)
    sftp = paramiko.SFTPClient.from_transport(t)
    print("[1] 上传 SDK 部署包 → %s:/opt/door-sdk" % HOST)
    sftp_put_dir(sftp, LOCAL, REMOTE)
    sftp.close()
    t.close()

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=15)

    def run(cmd, timeout=60):
        stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode("utf-8", "ignore")
        err = stderr.read().decode("utf-8", "ignore")
        code = stdout.channel.recv_exit_status()
        return code, out, err

    code, out, err = run("ls /opt/java21/bin/java && /opt/java21/bin/java -version 2>&1 | head -3")
    print("[2] JDK 检查:\n" + out + err)
    if code != 0:
        print("!! .60 无 JDK21，中止"); return

    code, out, err = run("chmod +x /opt/door-sdk/native/*.so && ls -la /opt/door-sdk/native/ | head -8")
    print("[3] so 库权限:\n" + out + err)

    print("[4] 运行真机探测（.76 登录/开门/加卡/查卡/删卡）...")
    cmd = ("export LD_LIBRARY_PATH=/opt/door-sdk/native; "
           "/opt/java21/bin/java -cp /opt/door-sdk/netsdk.jar:/opt/door-sdk/jna.jar:/opt/door-sdk "
           "probe.DoorSdkProbe 2>&1")
    code, out, err = run(cmd, timeout=120)
    print("=== 探测输出 ===")
    print(out)
    print(err)
    print("EXIT=%d" % code)
    ssh.close()

if __name__ == "__main__":
    main()
