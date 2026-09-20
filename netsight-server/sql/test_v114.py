# -*- coding: utf-8 -*-
"""第5周联调：工单闭环 + 备品备件 + 事件自动建单联动"""
import json
import time
import urllib.request
import urllib.parse
import urllib.error

BASE = 'http://localhost:8080'
TOKEN_HEADER = 'NetSightWebhookToken2026'


def http(method, path, body=None, headers=None, query=None):
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    url = BASE + path + ('?' + urllib.parse.urlencode(query) if query else '')
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'ignore')[:400]
    except Exception as e:
        return 0, str(e)


def check(name, cond, extra=''):
    print(('PASS' if cond else 'FAIL'), name, extra)


# 1. 登录
http('POST', '/auth/sms-code', query={'phone': '13800000000'})
time.sleep(1)
st, r = http('POST', '/auth/login', {'phone': '13800000000', 'code': '123456'})
token = r['data']['token']
auth = {'Authorization': 'Bearer ' + token}
check('登录', st == 200)

# 2. getRouters 菜单
st, r = http('GET', '/getRouters', headers=auth)
menus = [m['path'] for m in r['data']]
check('路由含/workorder+/spare', '/workorder' in menus and '/spare' in menus, str(menus))

# 3. 新增维修人员（张师傅/李师傅）
st, r = http('POST', '/workorder/repairer', {
    'name': '张师傅', 'phone': '13800000001', 'region': '1号楼/2号楼',
    'deviceTypes': '["network","camera"]', 'skills': '交换机维修,光纤熔接', 'status': 1}, auth)
check('新增维修人员', st == 200, str(r))
st, r = http('POST', '/workorder/repairer', {
    'name': '李师傅', 'phone': '13800000002', 'region': '3号楼',
    'deviceTypes': '["camera","door_controller"]', 'skills': '摄像头维修,门禁调试', 'status': 1}, auth)
check('新增维修人员2', st == 200)
st, r = http('GET', '/workorder/repairer/options', headers=auth)
repairers = r['data']
repairer1 = repairers[0]['id']
check('维修人员列表', len(repairers) == 2, str([x['name'] for x in repairers]))

# 4. webhook 离线告警（critical）→ 自动建单
st, r = http('POST', '/alert/push', {
    'alerts': [{
        'status': 'firing',
        'labels': {'alertname': '网络设备离线故障', 'severity': 'critical',
                   'device_name': 'SW-05', 'device_ip': '192.168.1.50',
                   'device_type': 'network', 'device_location': '3号楼机房'},
        'annotations': {'summary': '设备SW-05(192.168.1.50)离线故障',
                        'description': '设备部署位置：3号楼机房，设备已离线，需立即排查'},
        'generatorURL': 'http://gw/alert/offline-sw05',
        'startsAt': '2026-09-11T09:10:00Z'
    }]
}, {'X-Netsight-Webhook-Token': TOKEN_HEADER, 'X-Netsight-Tenant-Id': '1'})
check('webhook离线告警', st == 200, str(r)[:150])
time.sleep(3)  # 等异步处理+事件联动

st, r = http('GET', '/workorder/order/list?pageNum=1&pageSize=10', headers=auth)
orders = r['data']['rows']
check('自动生成工单', len(orders) == 1, str([(o['orderNo'], o['status'], o['faultType']) for o in orders]))
order = orders[0]
order_id = order['id']
check('工单状态待处理', order['status'] == 0)
check('工单设备信息快照', order['deviceName'] == 'SW-05' and order['deviceIp'] == '192.168.1.50')

# 5. 工单详情（含处理记录）
st, r = http('GET', '/workorder/order/%d' % order_id, headers=auth)
d = r['data']
check('工单详情记录', len(d['records']) == 1 and d['records'][0]['action'] == 'auto_create', str(d['records']))

# 6. 报修派单（选张师傅）→ 自动通知
st, r = http('POST', '/workorder/order/dispatch', {'id': order_id, 'repairerId': repairer1, 'remark': '请尽快上门'}, auth)
check('报修派单', st == 200, str(r))
time.sleep(2)
st, r = http('GET', '/workorder/order/list?pageNum=1&pageSize=10', headers=auth)
o = r['data']['rows'][0]
check('派单后状态=1/维修人员', o['status'] == 1 and o['repairerName'] == '张师傅')
st, r = http('GET', '/alert/log/list?pageNum=1&pageSize=5', headers=auth)
logs = r['data']['rows']
dispatch_logs = [l for l in logs if l['eventType'] == 'order_dispatch']
check('派单通知日志(sms+wechat)', len(dispatch_logs) == 2,
      str([(l['channelType'], l['success']) for l in dispatch_logs]))
if dispatch_logs:
    check('派单模板渲染', 'WO' in (dispatch_logs[0]['content'] or ''), str(dispatch_logs[0]['content'])[:120])

# 7. 开始维修 → 完工
st, r = http('POST', '/workorder/order/repairStart', {'id': order_id}, auth)
check('开始维修', st == 200)
st, r = http('POST', '/workorder/order/complete', {'id': order_id, 'repairResult': '更换光纤模块后恢复'}, auth)
check('完工', st == 200)
st, r = http('GET', '/workorder/order/list?pageNum=1&pageSize=10', headers=auth)
o = r['data']['rows'][0]
check('完工后状态=3', o['status'] == 3 and o['repairResult'] == '更换光纤模块后恢复')

# 8. 工单统计
st, r = http('GET', '/workorder/order/stats', headers=auth)
check('工单统计', r['data']['completed'] == 1, str(r['data']))

# 9. 新增备件
st, r = http('POST', '/spare/part', {
    'partType': 'POE交换机', 'brand': 'H3C', 'model': 'S5130S-28S-EI',
    'serialNo': 'SN2026090001', 'quantity': 5, 'unit': '台',
    'status': 'new', 'location': '机房备件柜A-3', 'safeStock': 2}, auth)
part_id = r['data']
check('新增备件', st == 200 and part_id)
st, r = http('POST', '/spare/part', {
    'partType': '光纤模块', 'brand': '华为', 'model': 'SFP-GE-LX-SM1310',
    'serialNo': 'SN2026090002', 'quantity': 10, 'unit': '个',
    'status': 'new', 'location': '机房备件柜B-1', 'safeStock': 3}, auth)
check('新增备件2', st == 200)

# 10. 领用出库（关联工单，扣减库存）
st, r = http('POST', '/spare/part/stock', {
    'partId': part_id, 'recordType': 'out', 'quantity': 2,
    'orderId': order_id, 'orderNo': order['orderNo'], 'remark': '更换光模块使用'}, auth)
check('领用出库', st == 200, str(r))
st, r = http('GET', '/spare/part/list?pageNum=1&pageSize=10', headers=auth)
parts = r['data']['rows']
p1 = next(p for p in parts if p['id'] == part_id)
check('出库后库存=3', p1['quantity'] == 3, str(p1['quantity']))

# 11. 工单备件关联 + 出入库记录
st, r = http('GET', '/workorder/order/%d' % order_id, headers=auth)
check('工单备件关联', len(r['data']['parts']) == 1 and r['data']['parts'][0]['quantity'] == 2)
st, r = http('GET', '/spare/record/list?pageNum=1&pageSize=10', headers=auth)
recs = r['data']['rows']
check('出入库记录(out+in)', len(recs) >= 2, str([(x['recordType'], x['quantity'], x.get('orderNo')) for x in recs]))

# 12. 旧件返修 → 返修入库
st, r = http('POST', '/spare/part/stock', {
    'partId': part_id, 'recordType': 'repair', 'quantity': 1,
    'remark': '换下旧件送修'}, auth)
check('返修状态流转', st == 200)
st, r = http('GET', '/spare/part/list?pageNum=1&pageSize=10', headers=auth)
p1 = next(p for p in r['data']['rows'] if p['id'] == part_id)
check('返修中状态', p1['status'] == 'repairing', str(p1['status']))
st, r = http('POST', '/spare/part/stock', {
    'partId': part_id, 'recordType': 'return_in', 'quantity': 1,
    'remark': '返修完成入库'}, auth)
st, r = http('GET', '/spare/part/list?pageNum=1&pageSize=10', headers=auth)
p1 = next(p for p in r['data']['rows'] if p['id'] == part_id)
check('返修入库后库存=4/已修复', p1['quantity'] == 4 and p1['status'] == 'repaired')

# 13. 库存不足校验
st, r = http('POST', '/spare/part/stock', {
    'partId': part_id, 'recordType': 'out', 'quantity': 99,
    'orderId': order_id, 'orderNo': order['orderNo']}, auth)
check('库存不足拦截', st == 200 and r['code'] != 200, str(r)[:100])

# 14. 设备恢复 → 未完成工单自动归档
st, r = http('POST', '/alert/push', {
    'alerts': [{
        'status': 'firing',
        'labels': {'alertname': '设备外线状态异常', 'severity': 'warning',
                   'device_name': 'CAM-09', 'device_ip': '192.168.1.60',
                   'device_type': 'camera', 'device_location': '2号楼走廊'},
        'annotations': {'summary': '设备CAM-09(192.168.1.60)外线异常',
                        'description': '设备外线链路衰减/错包异常'},
        'generatorURL': 'http://gw/alert/line-cam09',
        'startsAt': '2026-09-11T09:11:00Z'
    }]
}, {'X-Netsight-Webhook-Token': TOKEN_HEADER, 'X-Netsight-Tenant-Id': '1'})
time.sleep(3)
st, r = http('GET', '/workorder/order/list?pageNum=1&pageSize=10', headers=auth)
orders = r['data']['rows']
check('外线事件建单', len(orders) == 2, str([(o['orderNo'], o['faultType']) for o in orders]))
cam_order = next(o for o in orders if o['deviceIp'] == '192.168.1.60')
st, r = http('POST', '/alert/push', {
    'alerts': [{
        'status': 'resolved',
        'labels': {'alertname': '设备外线状态异常', 'severity': 'resolved',
                   'device_name': 'CAM-09', 'device_ip': '192.168.1.60',
                   'device_type': 'camera', 'device_location': '2号楼走廊'},
        'annotations': {'summary': '设备CAM-09(192.168.1.60)已恢复'},
        'generatorURL': 'http://gw/alert/line-cam09',
        'startsAt': '2026-09-11T09:12:00Z'
    }]
}, {'X-Netsight-Webhook-Token': TOKEN_HEADER, 'X-Netsight-Tenant-Id': '1'})
time.sleep(3)
st, r = http('GET', '/workorder/order/list?pageNum=1&pageSize=10', headers=auth)
cam_order2 = next(o for o in r['data']['rows'] if o['deviceIp'] == '192.168.1.60')
check('恢复自动归档(状态=5)', cam_order2['status'] == 5, str(cam_order2['status']))

# 15. 手动建单 + 关闭
st, r = http('POST', '/workorder/order', {
    'deviceName': '门禁控制器D-01', 'deviceIp': '192.168.1.70', 'deviceType': 'door_controller',
    'deviceLocation': '1号楼大厅', 'faultType': 'manual', 'severity': 'info',
    'description': '门禁刷卡异常，需现场检查'}, auth)
st, r = http('POST', '/workorder/order/close', {'id': r['data'], 'remark': '现场复位后恢复'}, auth)
check('手动建单+关闭', st == 200)

print('=== 联调完成 ===')
