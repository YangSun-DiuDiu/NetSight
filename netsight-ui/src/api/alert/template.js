import request from '@/utils/request'

// 通知消息模板（NetSight 第 4 周）

// 模板分页查询
export function listTemplate(query) {
  return request({
    url: '/alert/template/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 模板下拉（规则配置选择模板用）
export function listTemplateOptions(channelType) {
  return request({
    url: '/alert/template/options',
    method: 'get',
    params: channelType ? { channelType: channelType } : {}
  }).then(res => res.data)
}

// 新增模板
export function addTemplate(data) {
  return request({
    url: '/alert/template',
    method: 'post',
    data: data
  })
}

// 修改模板
export function updateTemplate(data) {
  return request({
    url: '/alert/template',
    method: 'put',
    data: data
  })
}

// 删除模板
export function delTemplate(id) {
  return request({
    url: '/alert/template/' + id,
    method: 'delete'
  })
}
