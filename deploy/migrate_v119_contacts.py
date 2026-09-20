import paramiko, json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('192.168.1.55', 22, 'root', 'Chinaunicom@1358', timeout=15)

def run(cmd):
    stdin, stdout, stderr = c.exec_command(cmd)
    out = stdout.read().decode('utf-8', 'replace')
    err = stderr.read().decode('utf-8', 'replace')
    return out.strip(), err.strip()

MYSQL = 'mysql -usadmin -pChinaunicom@1358 netsight --default-character-set=utf8mb4 -N -e '

# 1) 取所有规则 id | tenant_id | receiver_strategy_json
out, _ = run(MYSQL + '"SELECT id, tenant_id, receiver_strategy_json FROM notification_rule WHERE del_flag=0;"')
rules = []
for line in out.splitlines():
    parts = line.split('\t')
    if len(parts) < 3 or not parts[2]:
        continue
    rid, tid, js = parts[0], parts[1], parts[2]
    try:
        obj = json.loads(js)
    except Exception:
        continue
    if not isinstance(obj, dict):
        continue
    receivers = obj.get('receivers')
    if not receivers:
        continue  # 已是 contactIds 格式或空，跳过
    rules.append((rid, tid, receivers))

print('待迁移规则数:', len(rules))

# 2) 按 (tenant_id, mobile) upsert notify_contact，返回 contactId
cache = {}   # (tid, mobile) -> contactId
def ensure_contact(tid, mobile):
    key = (tid, mobile)
    if key in cache:
        return cache[key]
    # 查现有
    out2, _ = run(MYSQL + '"SELECT id FROM notify_contact WHERE tenant_id=%s AND mobile=\'%s\' AND del_flag=0 LIMIT 1;"' % (tid, mobile))
    cid = out2.strip()
    if cid:
        cache[key] = cid
        return cid
    # 新建：name 用"联系人-手机后4位"
    name = '联系人' + mobile[-4:] if len(mobile) >= 4 else '联系人'
    run(MYSQL + '"INSERT INTO notify_contact(tenant_id,name,mobile,wechat_openid,status,create_by,create_time,update_time,del_flag) VALUES(%s,\'%s\',\'%s\',\'\',1,\'系统迁移\',NOW(),NOW(),0);"' % (tid, name, mobile))
    out3, _ = run(MYSQL + '"SELECT id FROM notify_contact WHERE tenant_id=%s AND mobile=\'%s\' AND del_flag=0 ORDER BY id DESC LIMIT 1;"' % (tid, mobile))
    cid = out3.strip()
    cache[key] = cid
    print('  新建联系人 tid=%s mobile=%s -> id=%s' % (tid, mobile, cid))
    return cid

# 3) 逐条改 receiver_strategy_json
for rid, tid, receivers in rules:
    ids = []
    for m in receivers:
        m = str(m).strip()
        if not m:
            continue
        cid = ensure_contact(tid, m)
        if cid:
            ids.append(int(cid))
    new_json = json.dumps({'type': 'fixed', 'contactIds': ids}, ensure_ascii=False)
    # 转义单引号
    new_json_sql = new_json.replace("'", "''")
    run(MYSQL + '"UPDATE notification_rule SET receiver_strategy_json=\'%s\' WHERE id=%s;"' % (new_json_sql, rid))
    print('  规则 id=%s tid=%s receivers=%s -> contactIds=%s' % (rid, tid, receivers, ids))

# 4) 校验
out, _ = run(MYSQL + '"SELECT id, receiver_strategy_json FROM notification_rule WHERE del_flag=0;"')
print('=== 迁移后 ===')
print(out)
print('=== notify_contact ===')
out, _ = run(MYSQL + '"SELECT id, tenant_id, name, mobile, status FROM notify_contact WHERE del_flag=0;"')
print(out)
c.close()
print('DONE')
