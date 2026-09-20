import paramiko
host, user, pwd = "192.168.1.60", "root", "Chinaunicom@1358"
cli = paramiko.SSHClient()
cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    cli.connect(host, username=user, password=pwd, timeout=10)
    unit = """[Unit]
Description=NetSight Edge Gateway Simulator
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/nams-gateway-sim
ExecStart=/usr/bin/python3 /opt/nams-gateway-sim/gateway_sim.py /opt/nams-gateway-sim/config.json
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
"""
    sftp = cli.open_sftp()
    with sftp.open("/etc/systemd/system/nams-gateway-sim.service", "w") as f:
        f.write(unit)
    sftp.close()
    stdin, stdout, stderr = cli.exec_command("systemctl daemon-reload && systemctl enable nams-gateway-sim && systemctl restart nams-gateway-sim && sleep 3 && systemctl status nams-gateway-sim --no-pager -l | head -12")
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err:
        print("ERR:", err)
finally:
    cli.close()
