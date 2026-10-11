import request from '@/utils/request'

// 运维监控大屏（NetSight 第 6 周）

// 大屏聚合数据（设备概览/事件统计/工单看板/网关状态/备件库存）
// demo=1：演示模式（每个板块 100 条级模拟数据，丰富展示效果，不触碰真实业务表）
export function getDashboardOverview(demo = 0) {
  return request({
    url: '/dashboard/overview',
    method: 'get',
    params: { demo }
  }).then(res => res.data)
}
