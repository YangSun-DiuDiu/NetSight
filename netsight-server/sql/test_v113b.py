# -*- coding: utf-8 -*-
"""手动发送 + 规则/模板接口联调"""
import json
import time
import urllib.request
import urllib.parse

BASE = 'http://localhost:8080'


def http(method, path, body=None, headers=None, query=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    url = BASE + path + ('?' + urllib.parse.urlencode(query) if query else '')
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'ignore')[:300]


# 登录
http('POST', '/auth/sms-code', query={'phone': '13800000000'})
time.sleep(1)
st, r = http('POST', '/auth/login', {'phone': '13800000000', 'code': '123456'})
token = r['data']['token']
auth = {'Authorization': 'Bearer ' + token}
print('login ok')

# 1. 手动发送
st, r = http('POST', '/alert/event/manual', {
    'eventType': 'manual_notify',
    'content': '周末机房巡检通知：请各值班人员确认',
    'receivers': ['13800000000', '13900000000'],
    'channels': ['sms', 'wechat']
}, auth)
print('manual send:', st, r)
time.sleep(2)

# 2. 规则 CRUD
st, r = http('POST', '/alert/rule', {
    'ruleName': '测试规则-外线', 'eventType': 'device_line_abnormal',
    'conditionJson': '{"severity":"critical"}',
    'receiverStrategyJson': '{"type":"fixed","receivers":["13800000000"]}',
    'channelsJson': '["sms"]', 'templateId': 3, 'enabled': 1
}, auth)
print('rule add:', st, r)
st, r = http('GET', '/alert/rule/list?pageNum=1&pageSize=10', headers=auth)
print('rule list total:', r['data']['total'])
st, r = http('DELETE', '/alert/rule/5', headers=auth)
print('rule del:', st, r)

# 3. 模板 options
st, r = http('GET', '/alert/template/options', headers=auth)
print('template options:', st, [t['templateCode'] + '-' + t['channelType'] for t in r['data']])

# 4. 日志列表
st, r = http('GET', '/alert/log/list?pageNum=1&pageSize=5', headers=auth)
print('log total:', r['data']['total'])
for l in r['data']['rows'][:3]:
    print('  log:', l['id'], l['channelType'], 'success=', l['success'],
          'content=', (l['content'] or '')[:30])
