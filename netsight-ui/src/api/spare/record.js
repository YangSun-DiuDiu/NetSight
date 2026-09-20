import request from '@/utils/request'

// 出入库记录分页查询
export function listRecord(query) {
  return request({
    url: '/spare/record/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}
