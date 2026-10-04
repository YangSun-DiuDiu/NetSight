import paramiko
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect('192.168.1.55', username='root', password='Chinaunicom@1358', timeout=10)
# Search all CSS files for the new colors
stdin, stdout, stderr = ssh.exec_command('grep -rl "e8743b\\|fff4e6" /opt/nams-ui/dist/static/css/ 2>/dev/null')
result = stdout.read().decode().strip()
print("CSS files with warm orange:", result if result else "NOT FOUND")
# Also check the sidebar CSS specifically
stdin, stdout, stderr = ssh.exec_command('ls -lt /opt/nams-ui/dist/static/css/ | head -5')
print("\nLatest CSS files:")
print(stdout.read().decode())
ssh.close()
