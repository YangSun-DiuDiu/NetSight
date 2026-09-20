import request from '@/utils/request'

// 维修人员分页查询
export function listRepairer(query) {
  return request({
    url: '/workorder/repairer/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 在岗维修人员选项（派单弹窗）
export function listRepairerOptions() {
  return request({
    url: '/workorder/repairer/options',
    method: 'get'
  }).then(res => res.data)
}

// 新增维修人员
export function addRepairer(data) {
  return request({
    url: '/workorder/repairer',
    method: 'post',
    data: data
  }).then(res => res.data)
}

// 修改维修人员
export function updateRepairer(data) {
  return request({
    url: '/workorder/repairer',
    method: 'put',
    data: data
  }).then(res => res.data)
}

// 删除维修人员
export function delRepairer(id) {
  return request({
    url: '/workorder/repairer/' + id,
    method: 'delete'
  }).then(res => res.data)
}
