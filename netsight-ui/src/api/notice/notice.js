import request from '@/utils/request'

// 通知公告（NetSight V1.2.3）

// 管理端：公告分页查询
export function listNotice(query) {
  return request({
    url: '/notice/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 管理端：公告统计
export function getNoticeStats() {
  return request({
    url: '/notice/stats',
    method: 'get'
  }).then(res => res.data)
}

// 用户端：已发布公告列表（含已读标识）
export function listPublishedNotice() {
  return request({
    url: '/notice/published',
    method: 'get'
  }).then(res => res.data)
}

// 用户端：未读公告数
export function getUnreadNoticeCount() {
  return request({
    url: '/notice/unread-count',
    method: 'get'
  }).then(res => res.data)
}

// 公告详情（自动标记已读）
export function getNotice(id) {
  return request({
    url: '/notice/' + id,
    method: 'get'
  }).then(res => res.data)
}

// 新增公告
export function addNotice(data) {
  return request({
    url: '/notice',
    method: 'post',
    data: data
  })
}

// 修改公告
export function updateNotice(data) {
  return request({
    url: '/notice',
    method: 'put',
    data: data
  })
}

// 删除公告
export function delNotice(id) {
  return request({
    url: '/notice/' + id,
    method: 'delete'
  })
}

// 发布公告
export function publishNotice(id) {
  return request({
    url: '/notice/' + id + '/publish',
    method: 'post'
  })
}

// 下线公告
export function offlineNotice(id) {
  return request({
    url: '/notice/' + id + '/offline',
    method: 'post'
  })
}
