import request from '@/utils/request'

// 查询操作日志列表
export function listOperationLog(query) {
  return request({
    url: '/alert/event/operation-log',
    method: 'get',
    params: query
  })
}
