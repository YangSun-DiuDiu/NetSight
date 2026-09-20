import request from '@/utils/request'

// 菜单/权限管理（NetSight 后端接口对齐版）

// 查询权限树（角色分配权限用）
export function menuTree() {
  return request({
    url: '/system/menu/tree',
    method: 'get'
  }).then(res => res.data)
}
