import request from '@/utils/request'

// 通知路由规则（自动发送策略，NetSight 第 4 周）

// 规则分页查询
export function listRule(query) {
  return request({
    url: '/alert/rule/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 新增规则
export function addRule(data) {
  return request({
    url: '/alert/rule',
    method: 'post',
    data: data
  })
}

// 修改规则
export function updateRule(data) {
  return request({
    url: '/alert/rule',
    method: 'put',
    data: data
  })
}

// 删除规则
export function delRule(id) {
  return request({
    url: '/alert/rule/' + id,
    method: 'delete'
  })
}
