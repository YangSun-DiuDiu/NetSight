import request from '@/utils/request'

// 告警事件中心（NetSight 第 4 周）

// 事件分页查询
export function listEvent(query) {
  return request({
    url: '/alert/event/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

// 手动发送通知（管理员主动触发）
export function manualSend(data) {
  return request({
    url: '/alert/event/manual',
    method: 'post',
    data: data
  })
}
