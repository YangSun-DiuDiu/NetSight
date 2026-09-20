import request from '@/utils/request'

// 运维监控大屏（NetSight 第 6 周）

// 大屏聚合数据（设备概览/事件统计/工单看板/网关状态/备件库存）
export function getDashboardOverview() {
  return request({
    url: '/dashboard/overview',
    method: 'get'
  }).then(res => res.data)
}
