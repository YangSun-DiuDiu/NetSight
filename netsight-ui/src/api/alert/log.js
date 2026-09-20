import request from '@/utils/request'

// 通知发送日志（NetSight 第 4 周）

// 发送日志分页查询
export function listLog(query) {
  return request({
    url: '/alert/log/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}
