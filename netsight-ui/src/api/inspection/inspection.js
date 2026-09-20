import request from '@/utils/request'

// 点检巡检（NetSight V1.2.4）

// ============ 点检项库 ============
export function listInspectionItem(query) {
  return request({
    url: '/inspection/item/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 启用的点检项（执行任务时选择）
export function listEnabledItem() {
  return request({
    url: '/inspection/item/enabled',
    method: 'get'
  }).then(res => res.data)
}

export function addInspectionItem(data) {
  return request({
    url: '/inspection/item',
    method: 'post',
    data: data
  })
}

export function updateInspectionItem(data) {
  return request({
    url: '/inspection/item',
    method: 'put',
    data: data
  })
}

export function delInspectionItem(id) {
  return request({
    url: '/inspection/item/' + id,
    method: 'delete'
  })
}

// ============ 点检计划 ============
export function listInspectionPlan(query) {
  return request({
    url: '/inspection/plan/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

export function addInspectionPlan(data) {
  return request({
    url: '/inspection/plan',
    method: 'post',
    data: data
  })
}

export function updateInspectionPlan(data) {
  return request({
    url: '/inspection/plan',
    method: 'put',
    data: data
  })
}

export function delInspectionPlan(id) {
  return request({
    url: '/inspection/plan/' + id,
    method: 'delete'
  })
}

// 按计划生成指定日期任务（默认今天）
export function generateInspectionTask(planId, date) {
  return request({
    url: '/inspection/plan/' + planId + '/generate',
    method: 'post',
    params: date ? { date: date } : {}
  }).then(res => res.data)
}

// ============ 点检任务 ============
export function listInspectionTask(query) {
  return request({
    url: '/inspection/task/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

export function getInspectionTask(id) {
  return request({
    url: '/inspection/task/' + id,
    method: 'get'
  }).then(res => res.data)
}

export function startInspectionTask(id) {
  return request({
    url: '/inspection/task/' + id + '/start',
    method: 'post'
  })
}

export function submitInspectionTask(data) {
  return request({
    url: '/inspection/task/submit',
    method: 'post',
    data: data
  }).then(res => res.data)
}

export function getInspectionTaskStats() {
  return request({
    url: '/inspection/task/stats',
    method: 'get'
  }).then(res => res.data)
}

// ============ 点检记录 ============
export function listInspectionRecord(query) {
  return request({
    url: '/inspection/record/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}
