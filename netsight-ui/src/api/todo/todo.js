import request from '@/utils/request'

// 待办统计（Navbar 角标 + 页面统计卡）
export function getTodoStats() {
  return request({
    url: '/todo/stats',
    method: 'get'
  }).then(res => res.data)
}

// 待办分页（type=all|work_order|inspection）
export function listTodo(query) {
  return request({
    url: '/todo/page',
    method: 'get',
    params: query
  }).then(res => res.data)
}
