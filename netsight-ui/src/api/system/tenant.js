import request from '@/utils/request'

// 租户管理
export function listTenant(query) {
  return request({
    url: '/system/tenant/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

export function addTenant(data) {
  return request({
    url: '/system/tenant',
    method: 'post',
    data: data
  })
}

export function updateTenant(data) {
  return request({
    url: '/system/tenant',
    method: 'put',
    data: data
  })
}

export function delTenant(id) {
  return request({
    url: '/system/tenant/' + id,
    method: 'delete'
  })
}

// 租户级 Webhook Token（方案 5.5 第 7 小节）
export function getWebhookToken(id) {
  return request({
    url: '/system/tenant/' + id + '/webhook-token',
    method: 'get'
  }).then(res => res.data) // 解包返回 token 字符串
}

export function resetWebhookToken(id) {
  return request({
    url: '/system/tenant/' + id + '/webhook-token/reset',
    method: 'post'
  }).then(res => res.data) // 解包返回新 token 字符串
}

// 租户级 PushPlus Token（GET 查看明文 / PUT 配置 / DELETE 清空）
export function getPushplusToken(id) {
  return request({
    url: '/system/tenant/' + id + '/pushplus-token',
    method: 'get'
  }).then(res => res.data)
}

export function setPushplusToken(id, token) {
  return request({
    url: '/system/tenant/' + id + '/pushplus-token',
    method: 'put',
    data: { token: token }
  }).then(res => res.data)
}

export function clearPushplusToken(id) {
  return request({
    url: '/system/tenant/' + id + '/pushplus-token',
    method: 'delete'
  })
}
