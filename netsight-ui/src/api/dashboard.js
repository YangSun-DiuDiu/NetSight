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

// ===== 驾驶舱上墙配置（3×3 上墙模式，按租户隔离） =====

// 查询当前租户上墙配置（返回有序 [{boardKey, boardName}]）
export function getWallConfig() {
  return request({
    url: '/dashboard/wall/config',
    method: 'get'
  }).then(res => res.data)
}

// 全量覆盖保存上墙配置（body: 有序 boardKey 数组，最多 9 个；传空数组=全部不上墙）
export function saveWallConfig(boardKeys) {
  return request({
    url: '/dashboard/wall/config',
    method: 'post',
    data: boardKeys
  }).then(res => res.data)
}
