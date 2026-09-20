import request from '@/utils/request'

// 备件分页查询
export function listPart(query) {
  return request({
    url: '/spare/part/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 备件统计
export function getPartStats() {
  return request({
    url: '/spare/part/stats',
    method: 'get'
  }).then(res => res.data)
}

// 备件类型列表（筛选下拉）
export function listPartTypes() {
  return request({
    url: '/spare/part/types',
    method: 'get'
  }).then(res => res.data)
}

// 可用备件选项（领用弹窗）
export function listAvailableParts() {
  return request({
    url: '/spare/part/available',
    method: 'get'
  }).then(res => res.data)
}

// 新增备件
export function addPart(data) {
  return request({
    url: '/spare/part',
    method: 'post',
    data: data
  }).then(res => res.data)
}

// 修改备件
export function updatePart(data) {
  return request({
    url: '/spare/part',
    method: 'put',
    data: data
  }).then(res => res.data)
}

// 删除备件
export function delPart(id) {
  return request({
    url: '/spare/part/' + id,
    method: 'delete'
  }).then(res => res.data)
}

// 出入库操作
export function stockPart(data) {
  return request({
    url: '/spare/part/stock',
    method: 'post',
    data: data
  }).then(res => res.data)
}
