<template>
  <div class="m-orders linear-theme">
    <van-nav-bar title="我的工单" class="light-navbar">
      <template slot="right">
        <van-icon name="share" size="18" color="#9ca3af" @click="handleLogout" />
      </template>
    </van-nav-bar>
    <van-tabs v-model="active" @change="onTabChange" class="light-tabs" line-width="24px">
      <van-tab title="待接单" name="1" />
      <van-tab title="维修中" name="2" />
      <van-tab title="已完成" name="3" />
    </van-tabs>
    <van-pull-refresh v-model="refreshing" @refresh="loadData">
      <van-list
        v-model="loading"
        :finished="finished"
        finished-text="没有更多了"
        @load="onLoad"
      >
        <div
          v-for="item in list"
          :key="item.id"
          class="order-card"
          @click="goDetail(item.id)"
        >
          <div class="card-header">
            <span class="order-no">{{ item.orderNo }}</span>
            <van-tag :type="statusType(item.status)">{{ item.statusText }}</van-tag>
          </div>
          <div class="card-body">
            <div class="row"><span class="label">设备</span><span>{{ item.deviceName || '-' }}</span></div>
            <div class="row"><span class="label">位置</span><span>{{ item.deviceLocation || '-' }}</span></div>
            <div class="row"><span class="label">故障</span><span>{{ faultText(item.faultType) }}</span></div>
            <div class="row"><span class="label">时间</span><span>{{ item.dispatchTime || '-' }}</span></div>
          </div>
        </div>
        <van-empty v-if="!loading && list.length === 0" description="暂无工单" />
      </van-list>
    </van-pull-refresh>
  </div>
</template>

<script>
import request from '@/utils/request'
import { removeToken } from '@/utils/auth'
import { Toast, Dialog } from 'vant'

export default {
  name: 'MOrders',
  data() {
    return {
      active: '1',
      list: [],
      loading: false,
      finished: false,
      refreshing: false,
      pageNum: 1,
      pageSize: 10
    }
  },
  created() {
    this.loadData()
  },
  methods: {
    loadData() {
      this.pageNum = 1
      this.list = []
      this.finished = false
      this.loading = false
      this.fetchList()
    },
    onTabChange() {
      this.loadData()
    },
    fetchList() {
      this.loading = true
      request.get('/m/order/my-list', {
        params: { status: this.active, pageNum: this.pageNum, pageSize: this.pageSize }
      }).then(res => {
        const rows = res.data.rows || []
        this.list = this.list.concat(rows)
        if (rows.length < this.pageSize) {
          this.finished = true
        } else {
          this.pageNum++
        }
        this.loading = false
        this.refreshing = false
      }).catch((err) => {
        this.loading = false
        this.refreshing = false
        this.finished = true  // 阻止 van-list 自动重试导致死循环
        // 未关联维修人员档案：清除 token，跳回登录页提示
        if (err && err.message && err.message.includes('维修人员')) {
          removeToken()
          localStorage.removeItem('Admin-User')
          Toast('当前账号未关联维修人员档案，请联系管理员')
          this.$router.replace('/m/login')
        }
      })
    },
    onLoad() {
      this.fetchList()
    },
    goDetail(id) {
      this.$router.push('/m/order/' + id)
    },
    handleLogout() {
      Dialog.confirm({
        title: '提示',
        message: '确定要退出登录吗？'
      }).then(() => {
        request.post('/auth/logout').finally(() => {
          removeToken()
          localStorage.removeItem('Admin-User')
          this.$router.replace('/m/login')
        })
      }).catch(() => {})
    },
    statusType(status) {
      const map = { 1: 'warning', 2: 'primary', 3: 'success' }
      return map[status] || 'default'
    },
    faultText(type) {
      const map = { offline: '设备离线', line_abnormal: '链路异常', manual: '手动建单' }
      return map[type] || type
    }
  }
}
</script>

<style scoped>
.linear-theme {
  --canvas: #f7f8f8;
  --surface: #ffffff;
  --hairline: #e6e7ea;
  --ink: #1f2937;
  --ink-muted: #6b7280;
  --ink-subtle: #9ca3af;
  --primary: #5e6ad2;

  min-height: 100vh;
  background: var(--canvas);
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', system-ui, sans-serif;
}

/* Navbar */
::v-deep .light-navbar {
  background: var(--surface);
  color: var(--ink);
  height: 56px;
}
::v-deep .light-navbar .van-nav-bar__title {
  color: var(--ink);
  font-weight: 600;
  font-size: 16px;
}
::v-deep .light-navbar::after {
  background-color: var(--hairline);
}

/* Tabs */
::v-deep .light-tabs {
  background: var(--surface);
}
::v-deep .light-tabs .van-tabs__wrap {
  background: var(--surface);
}
::v-deep .light-tabs .van-tab {
  color: var(--ink-subtle);
  font-size: 14px;
  font-weight: 500;
}
::v-deep .light-tabs .van-tab--active {
  color: var(--ink);
  font-weight: 600;
}
::v-deep .light-tabs .van-tabs__line {
  background-color: var(--primary);
}
::v-deep .light-tabs::after {
  background-color: var(--hairline);
}

/* Order cards */
.order-card {
  margin: 12px 16px 0;
  padding: 16px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
}
.order-card:active {
  background: #f9fafb;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.order-no {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
}
.card-body .row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ink-muted);
  line-height: 2;
}
.label {
  color: var(--ink-subtle);
}
</style>
