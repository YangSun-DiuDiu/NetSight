import request from '@/utils/request'

// 查询通知联系人列表
export function listContact(query) {
  return request({
    url: '/alert/contact/list',
    method: 'get',
    params: query
  })
}

// 下拉选项（本租户启用联系人）
export function contactOptions() {
  return request({
    url: '/alert/contact/options',
    method: 'get'
  }).then(res => res.data || res.rows || res)
}

// 新增联系人
export function addContact(data) {
  return request({
    url: '/alert/contact',
    method: 'post',
    data: data
  })
}

// 修改联系人
export function updateContact(data) {
  return request({
    url: '/alert/contact',
    method: 'put',
    data: data
  })
}

// 删除联系人
export function delContact(id) {
  return request({
    url: '/alert/contact/' + id,
    method: 'delete'
  })
}

// 生成联系人微信绑定二维码
export function getWechatBindUrl(id, channelId) {
  return request({
    url: `/alert/contact/${id}/wechat/bind-url`,
    method: 'get',
    params: { channelId }
  })
}

// 轮询扫码绑定结果
export function getWechatBindStatus(ticket) {
  return request({
    url: '/alert/contact/wechat/bind-status',
    method: 'get',
    params: { ticket }
  })
}

// 解绑联系人微信
export function unbindWechat(id) {
  return request({
    url: `/alert/contact/${id}/wechat/unbind`,
    method: 'post'
  })
}
