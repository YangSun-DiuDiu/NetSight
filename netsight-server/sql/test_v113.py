# -*- coding: utf-8 -*-
"""第4周联调脚本：登录 + webhook 推送 + 事件/日志查询"""
import json
import time
import urllib.request
import urllib.parse

BASE = 'http://localhost:8080'
WEBHOOK_TOKEN = 'NetSightWebhookToken2026'


def http(method, path, body=None, headers=None, query=None):
    data = None
    url = BASE + path
    if query:
        url += '?' + urllib.parse.urlencode(query)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'ignore')[:500]


# 1. 发验证码（mock 123456，限流10秒）
print('== 1.发送验证码 ==')
st, r = http('POST', '/auth/sms-code', query={'phone': '13800000000'})
print(st, r)

time.sleep(1)
# 2. 登录
print('== 2.登录 ==')
st, r = http('POST', '/auth/login', {'phone': '13800000000', 'code': '123456'})
print(st, 'code=', r.get('code') if isinstance(r, dict) else r)
token = None
if isinstance(r, dict) and r.get('data'):
    token = r['data'].get('token')
print('token:', (token or '')[:40], '...')
auth = {'Authorization': 'Bearer ' + token} if token else {}

# 3. 获取路由（验证告警中心菜单）
print('== 3.getRouters（检查 /alert 菜单）==')
st, r = http('GET', '/getRouters', headers=auth)
if isinstance(r, dict) and r.get('data'):
    for m in r['data']:
        print('顶级:', m.get('name'), m.get('path'), '->', [c.get('name') for c in (m.get('children') or [])])
else:
    print(st, r)

# 4. 推送 critical 设备离线事件（AlertManager v4 格式）
print('== 4.Webhook 推送 critical 离线 ==')
body = {
    "version": "4", "status": "firing", "receiver": "webhook-push",
    "commonLabels": {"alertname": "网络设备离线故障", "severity": "critical"},
    "commonAnnotations": {"summary": "设备SW-01离线", "description": "设备部署位置：1号楼机房，设备已离线，需立即排查"},
    "alerts": [{
        "status": "firing",
        "labels": {"alertname": "网络设备离线故障", "severity": "critical",
                   "device_ip": "192.168.1.10", "device_name": "SW-01",
                   "device_type": "network", "device_location": "1号楼机房"},
        "annotations": {"summary": "设备SW-01(192.168.1.10)离线故障",
                        "description": "设备部署位置：1号楼机房，设备已离线，需立即排查"},
        "startsAt": "2026-09-11T08:10:00+08:00", "generatorURL": "http://gw1:9093/graph#A"
    }]
}
st, r = http('POST', '/alert/push', body, {'X-Netsight-Webhook-Token': WEBHOOK_TOKEN, 'X-Netsight-Tenant-Id': '1'})
print(st, r)

# 5. 推送 warning 外线异常
print('== 5.Webhook 推送 warning 外线异常 ==')
body2 = {
    "status": "firing", "receiver": "webhook-push",
    "commonLabels": {"alertname": "设备外线状态异常", "severity": "warning"},
    "alerts": [{
        "status": "firing",
        "labels": {"alertname": "设备外线状态异常", "severity": "warning",
                   "device_ip": "192.168.1.20", "device_name": "CAM-02",
                   "device_type": "camera", "device_location": "2号楼走廊"},
        "annotations": {"summary": "设备CAM-02(192.168.1.20)外线异常",
                        "description": "设备外线链路衰减/错包异常，设备在线，建议巡检线路"},
        "startsAt": "2026-09-11T08:11:00+08:00", "generatorURL": "http://gw1:9093/graph#B"
    }]
}
st, r = http('POST', '/alert/push', body2, {'X-Netsight-Webhook-Token': WEBHOOK_TOKEN, 'X-Netsight-Tenant-Id': '1'})
print(st, r)

# 6. 推送 resolved 恢复
print('== 6.Webhook 推送 resolved 恢复 ==')
body3 = {
    "status": "resolved", "receiver": "webhook-push",
    "commonLabels": {"alertname": "网络设备离线故障", "severity": "critical"},
    "alerts": [{
        "status": "resolved",
        "labels": {"alertname": "网络设备离线故障", "severity": "critical",
                   "device_ip": "192.168.1.10", "device_name": "SW-01",
                   "device_type": "network", "device_location": "1号楼机房"},
        "annotations": {"summary": "设备SW-01恢复上线", "description": "设备已恢复"},
        "startsAt": "2026-09-11T08:10:00+08:00", "endsAt": "2026-09-11T08:30:00+08:00",
        "generatorURL": "http://gw1:9093/graph#A"
    }]
}
st, r = http('POST', '/alert/push', body3, {'X-Netsight-Webhook-Token': WEBHOOK_TOKEN, 'X-Netsight-Tenant-Id': '1'})
print(st, r)

# 7. 等待异步处理完成
print('== 7.等待异步处理 3 秒 ==')
time.sleep(3)

# 8. 查询事件列表
print('== 8.事件列表 ==')
st, r = http('GET', '/alert/event/list?pageNum=1&pageSize=10', headers=auth)
if isinstance(r, dict) and r.get('data'):
    total = r['data'].get('total')
    print('total:', total)
    for e in r['data'].get('rows', []):
        print(f"  [#{e['id']}] type={e['eventType']} sev={e['severity']} status={e['status']} "
              f"rule={e.get('ruleName')} logs={e.get('logCount')} device={e.get('deviceName')}/{e.get('deviceIp')}")
else:
    print(st, r)

# 9. 查询发送日志
print('== 9.发送日志 ==')
st, r = http('GET', '/alert/log/list?pageNum=1&pageSize=10', headers=auth)
if isinstance(r, dict) and r.get('data'):
    print('total:', r['data'].get('total'))
    for l in r['data'].get('rows', []):
        print(f"  [#{l['id']}] event={l.get('eventId')} ch={l['channelType']} success={l['success']} "
              f"thirdParty={l.get('thirdPartyMsgId')} rule={l.get('ruleName')}")
else:
    print(st, r)

print('DONE')
