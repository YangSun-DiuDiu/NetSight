import paramiko, os
host, user, pwd = "192.168.1.60", "root", "Chinaunicom@1358"
cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    cli.connect(host, username=user, password=pwd, timeout=10)
    sftp = cli.open_sftp()
    try:
        sftp.mkdir("/opt/nams-gateway-sim")
    except IOError:
        pass
    base = r"E:\gitee\NetSight1.0\deploy\gateway-sim"
    for fn in ["gateway_sim.py", "config.json", "devices.json"]:
        sftp.put(os.path.join(base, fn), "/opt/nams-gateway-sim/" + fn)
        print("uploaded", fn)
    sftp.close()
    stdin, stdout, stderr = cli.exec_command("python3 --version && ls -l /opt/nams-gateway-sim/")
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err:
        print("ERR:", err)
finally:
    cli.close()
