import request from '@/utils/request'

// 边缘网关管理（NetSight 第 3 周）

// 网关列表（分页）
export function listGateway(query) {
  return request({
    url: '/edge/gateway/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

// 网关下拉选项（设备表单用）
export function listGatewayOptions() {
  return request({
    url: '/edge/gateway/options',
    method: 'get'
  }).then(res => res.data)
}

// 新增网关（返回 gatewayToken 仅展示一次）
export function addGateway(data) {
  return request({
    url: '/edge/gateway',
    method: 'post',
    data: data
  })
}

// 修改网关
export function updateGateway(data) {
  return request({
    url: '/edge/gateway',
    method: 'put',
    data: data
  })
}

// 删除网关
export function delGateway(id) {
  return request({
    url: '/edge/gateway/' + id,
    method: 'delete'
  })
}

// 重置网关 Token
export function resetGatewayToken(id) {
  return request({
    url: '/edge/gateway/resetToken/' + id,
    method: 'put'
  })
}

// 查看网关 PushPlus Token（脱敏）
export function getGatewayPushplusToken(id) {
  return request({
    url: '/edge/gateway/' + id + '/pushplus-token',
    method: 'get'
  })
}

// 设置/清空网关 PushPlus Token（body: { token: '...' }，空串清空）
export function setGatewayPushplusToken(id, token) {
  return request({
    url: '/edge/gateway/' + id + '/pushplus-token',
    method: 'put',
    data: { token: token || '' }
  })
}
