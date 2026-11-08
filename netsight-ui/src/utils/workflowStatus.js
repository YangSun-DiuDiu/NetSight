/**
 * 工作流状态展示的统一兜底
 *
 * 正常情况下后端已返回 statusText / statusTag，
 * 这里只在旧数据或字段缺失时兜底，避免页面再渲染出数字码。
 */

const BILL_STATUS = {
  processing: { text: '审批中', tag: 'warning' },
  approved: { text: '已通过', tag: 'success' },
  rejected: { text: '已驳回', tag: 'danger' },
  canceled: { text: '已撤销', tag: 'info' }
}

export function billStatusText(code) {
  return (BILL_STATUS[code] || {}).text || code || '—'
}

export function billStatusTag(code) {
  return (BILL_STATUS[code] || {}).tag || 'info'
}
