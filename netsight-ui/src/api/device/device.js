import request from '@/utils/request'

// 设备资产管理（NetSight 第 3 周）

// 设备列表（分页，支持名称/类型/状态搜索）
export function listDevice(query) {
  return request({
    url: '/device/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

// 设备详情（含拓扑）
export function getDevice(id) {
  return request({
    url: '/device/' + id,
    method: 'get'
  }).then(res => res.data)
}

// 新增设备（含拓扑主备三路）
export function addDevice(data) {
  return request({
    url: '/device',
    method: 'post',
    data: data
  })
}

// 修改设备（含拓扑更新）
export function updateDevice(data) {
  return request({
    url: '/device',
    method: 'put',
    data: data
  })
}

// 删除设备（级联清理拓扑）
export function delDevice(id) {
  return request({
    url: '/device/' + id,
    method: 'delete'
  })
}

// 拓扑树数据（节点+连线，拓扑图渲染）
export function getTopologyTree() {
  return request({
    url: '/device/topology/tree',
    method: 'get'
  }).then(res => res.data)
}

// 设备二维码信息（V1.2.7：生成二维码标签用，返回精简设备信息）
export function getQrcodeInfo(id) {
  return request({
    url: '/device/qrcode/' + id,
    method: 'get'
  }).then(res => res.data)
}
