import request from '@/utils/request'

// 故障知识库（NetSight V1.2.5）

// 分页查询
export function listFaultArticle(query) {
  return request({
    url: '/knowledge/fault/list',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 详情（浏览计数 +1）
export function getFaultArticle(id) {
  return request({
    url: '/knowledge/fault/' + id,
    method: 'get'
  }).then(res => res.data)
}

// 统计（总数/启用/停用/浏览总量 + 分类分布）
export function getFaultStats() {
  return request({
    url: '/knowledge/fault/stats',
    method: 'get'
  }).then(res => res.data)
}

// 工单联动推荐（设备类型 + 故障类型匹配）
export function recommendFault(query) {
  return request({
    url: '/knowledge/fault/recommend',
    method: 'get',
    params: query
  }).then(res => res.data)
}

// 下拉选项（分类/设备类型/参考级别）
export function getFaultOptions() {
  return request({
    url: '/knowledge/fault/options',
    method: 'get'
  }).then(res => res.data)
}

// 新增
export function addFaultArticle(data) {
  return request({
    url: '/knowledge/fault',
    method: 'post',
    data: data
  })
}

// 修改
export function updateFaultArticle(data) {
  return request({
    url: '/knowledge/fault',
    method: 'put',
    data: data
  })
}

// 删除
export function delFaultArticle(id) {
  return request({
    url: '/knowledge/fault/' + id,
    method: 'delete'
  })
}
