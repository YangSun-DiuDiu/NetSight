import request from '@/utils/request'

// 渠道类型元数据（动态表单）
export function getChannelMeta() {
  return request({ url: '/alert/channel/meta', method: 'get' }).then(res => res.data)
}

// 渠道分页
export function listChannel(query) {
  return request({ url: '/alert/channel/list', method: 'get', params: query }).then(res => res.data)
}

// 渠道下拉选项
export function channelOptions() {
  return request({ url: '/alert/channel/options', method: 'get' }).then(res => res.data)
}

// 渠道详情
export function getChannel(id) {
  return request({ url: '/alert/channel/' + id, method: 'get' }).then(res => res.data)
}

// 新增
export function addChannel(data) {
  return request({ url: '/alert/channel', method: 'post', data })
}

// 修改
export function updateChannel(data) {
  return request({ url: '/alert/channel', method: 'put', data })
}

// 删除
export function delChannel(id) {
  return request({ url: '/alert/channel/' + id, method: 'delete' })
}
