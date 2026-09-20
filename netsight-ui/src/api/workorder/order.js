import request from '@/utils/request'

// 工单分页查询
export function listOrder(query) {
  return request({
    url: '/workorder/order/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 工单统计
export function getOrderStats() {
  return request({
    url: '/workorder/order/stats',
    method: 'get'
  }).then(res => res.data)
}

// 工单详情（含处理记录+备件关联）
export function getOrder(id) {
  return request({
    url: '/workorder/order/' + id,
    method: 'get'
  }).then(res => res.data)
}

// 手动新增工单
export function addOrder(data) {
  return request({
    url: '/workorder/order',
    method: 'post',
    data: data
  }).then(res => res.data)
}

// 修改工单
export function updateOrder(data) {
  return request({
    url: '/workorder/order',
    method: 'put',
    data: data
  }).then(res => res.data)
}

// 删除工单
export function delOrder(id) {
  return request({
    url: '/workorder/order/' + id,
    method: 'delete'
  }).then(res => res.data)
}

// 报修派单（自动通知维修人员）
export function dispatchOrder(data) {
  return request({
    url: '/workorder/order/dispatch',
    method: 'post',
    data: data
  }).then(res => res.data)
}

// 开始维修
export function repairStartOrder(id) {
  return request({
    url: '/workorder/order/repairStart',
    method: 'post',
    data: { id: id }
  }).then(res => res.data)
}

// 完工
export function completeOrder(data) {
  return request({
    url: '/workorder/order/complete',
    method: 'post',
    data: data
  }).then(res => res.data)
}

// 关闭工单
export function closeOrder(data) {
  return request({
    url: '/workorder/order/close',
    method: 'post',
    data: data
  }).then(res => res.data)
}
