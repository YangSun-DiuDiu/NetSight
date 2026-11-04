import request from '@/utils/request'

// 流程定义列表
export function listDefinitions() {
  return request({
    url: '/workflow/definitions',
    method: 'get'
  }).then(res => res.data)
}

// 发起流程
export function startFlow(data) {
  return request({
    url: '/workflow/start',
    method: 'post',
    data
  }).then(res => res.data)
}

// 审批（通过/驳回）
export function approveFlow(data) {
  return request({
    url: '/workflow/approve',
    method: 'post',
    data
  }).then(res => res.data)
}

// 撤销流程
export function revokeFlow(data) {
  return request({
    url: '/workflow/revoke',
    method: 'post',
    data
  }).then(res => res.data)
}

// 我的待办
export function listMyTodo() {
  return request({
    url: '/workflow/mine/todo',
    method: 'get'
  }).then(res => res.data)
}

// 我发起的
export function listMyStarted() {
  return request({
    url: '/workflow/mine/started',
    method: 'get'
  }).then(res => res.data)
}

// 我审批过的（历史已办）
export function listMyDone(params) {
  return request({
    url: '/workflow/mine/done',
    method: 'get',
    params
  }).then(res => res.data)
}

// 审批单详情 + 流程历史
export function getBillDetail(billId) {
  return request({
    url: `/workflow/bill/${billId}`,
    method: 'get'
  }).then(res => res.data)
}

// 查询预置模板库（租户1）的全部流程定义，供超管勾选复制
export function listSourceTemplates() {
  return request({
    url: '/workflow/templates/source-list',
    method: 'get'
  }).then(res => res.data)
}

// 选择性复制流程模板到目标租户（超管）
export function copyTemplates(data) {
  return request({
    url: '/workflow/template/copy',
    method: 'post',
    data
  }).then(res => res.data)
}
