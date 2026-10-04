import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

/**
 * H5 移动端路由（维修人员工单反馈）
 * 访问入口：/m/
 */
const mobileRoutes = [
  {
    path: '/m/login',
    name: 'MLogin',
    component: () => import('@/views/m/login'),
    hidden: true,
    meta: { title: '登录' }
  },
  {
    path: '/m/orders',
    name: 'MOrders',
    component: () => import('@/views/m/orders'),
    hidden: true,
    meta: { title: '我的工单', requireAuth: true }
  },
  {
    path: '/m/order/:id',
    name: 'MOrderDetail',
    component: () => import('@/views/m/order-detail'),
    hidden: true,
    meta: { title: '工单详情', requireAuth: true }
  }
]

export default mobileRoutes
