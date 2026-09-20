import request from '@/utils/request'

// 角色管理（NetSight 后端接口对齐版）

// 查询角色列表（分页）
export function listRole(query) {
  return request({
    url: '/system/role/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

// 查询全部角色（下拉框用）
export function allRole() {
  return request({
    url: '/system/role/all',
    method: 'get'
  }).then(res => res.data)
}

// 新增角色
export function addRole(data) {
  return request({
    url: '/system/role',
    method: 'post',
    data: data
  })
}

// 修改角色
export function updateRole(data) {
  return request({
    url: '/system/role',
    method: 'put',
    data: data
  })
}

// 删除角色
export function delRole(roleId) {
  return request({
    url: '/system/role/' + roleId,
    method: 'delete'
  })
}

// 查询角色已分配的权限ID列表
export function getRolePermIds(roleId) {
  return request({
    url: '/system/role/permIds/' + roleId,
    method: 'get'
  }).then(res => res.data)
}
