import request from '@/utils/request'

// 用户管理（NetSight 后端接口对齐版）

// 查询用户列表（分页）
export function listUser(query) {
  return request({
    url: '/system/user/list',
    method: 'get',
    params: query
  }).then(res => res.data) // 解包 {total, rows}
}

// 新增用户
export function addUser(data) {
  return request({
    url: '/system/user',
    method: 'post',
    data: data
  })
}

// 修改用户
export function updateUser(data) {
  return request({
    url: '/system/user',
    method: 'put',
    data: data
  })
}

// 删除用户
export function delUser(userId) {
  return request({
    url: '/system/user/' + userId,
    method: 'delete'
  })
}

// 重置密码（恢复默认密码 admin123）
export function resetUserPwd(userId) {
  return request({
    url: '/system/user/resetPwd/' + userId,
    method: 'put'
  })
}

// 切换启用/禁用状态
export function changeUserStatus(userId, status) {
  return request({
    url: '/system/user/status/' + userId + '/' + status,
    method: 'put'
  })
}

// 查询用户已分配的角色ID列表
export function getUserRoleIds(userId) {
  return request({
    url: '/system/user/roleIds/' + userId,
    method: 'get'
  }).then(res => res.data)
}
