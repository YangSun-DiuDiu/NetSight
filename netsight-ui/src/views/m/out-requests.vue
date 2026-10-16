<template>
  <div class="m-out-requests linear-theme">
    <van-nav-bar
      title="我的领料申请"
      left-arrow
      @click-left="$router.back()"
      class="light-navbar"
    />
    <van-pull-refresh v-model="refreshing" @refresh="loadData">
      <van-list
        v-model="loading"
        :finished="finished"
        finished-text="没有更多了"
        @load="onLoad"
      >
        <div
          v-for="o in list"
          :key="o.id"
          class="req-card"
        >
          <div class="card-header">
            <span class="req-biz">{{ o.bizId }}</span>
            <van-tag :type="outStatusType(o.approvalStatus)">{{ outStatusText(o.approvalStatus) }}</van-tag>
          </div>
          <div class="card-body">
            <div class="row"><span class="label">工单</span><span>{{ o.orderNo || '-' }}</span></div>
            <div class="row"><span class="label">备件</span><span>{{ outItemsText(o.items) }}</span></div>
            <div class="row"><span class="label">发起时间</span><span>{{ o.createTime }}</span></div>
            <div class="row" v-if="o.remark"><span class="label">备注</span><span>{{ o.remark }}</span></div>
          </div>
          <div class="card-actions" v-if="o.approvalStatus === 'rejected'">
            <van-button
              size="small"
              plain
              type="primary"
              :loading="resubmittingBiz === o.bizId"
              @click="resubmit(o.bizId)"
            >重新提交</van-button>
          </div>
          <div class="card-actions" v-if="o.approvalStatus === 'processing'">
            <span class="hint">审批中，可前往【统一待办】跟踪</span>
          </div>
        </div>
        <van-empty v-if="!loading && list.length === 0" description="暂无领料申请" />
      </van-list>
    </van-pull-refresh>
  </div>
</template>

<script>
import request from '@/utils/request'
import { Toast } from 'vant'

export default {
  name: 'MOutRequests',
  data() {
    return {
      list: [],
      loading: false,
      finished: false,
      refreshing: false,
      pageNum: 1,
      pageSize: 20,
      resubmittingBiz: ''
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
    fetchList() {
      this.loading = true
      request.get('/spare/out/mine').then(res => {
        const rows = res.data || []
        this.list = rows
        this.finished = true
        this.loading = false
        this.refreshing = false
      }).catch(() => {
        this.loading = false
        this.refreshing = false
        this.finished = true
      })
    },
    onLoad() {
      this.fetchList()
    },
    resubmit(bizId) {
      this.resubmittingBiz = bizId
      request.post('/spare/out/resubmit', { bizId: bizId }).then(() => {
        this.resubmittingBiz = ''
        Toast('已重新提交，等待审批')
        this.loadData()
      }).catch(err => {
        this.resubmittingBiz = ''
        Toast((err && err.msg) || '重新提交失败')
      })
    },
    outItemsText(items) {
      if (!items) return ''
      try {
        const arr = JSON.parse(items)
        return arr.map(i => (i.partName || i.partId) + ' x' + i.quantity).join('，')
      } catch (e) {
        return items
      }
    },
    outStatusText(status) {
      const map = { processing: '审批中', approved: '已通过', rejected: '已驳回', canceled: '已撤销' }
      return map[status] || status
    },
    outStatusType(status) {
      const map = { processing: 'warning', approved: 'success', rejected: 'danger', canceled: 'default' }
      return map[status] || 'default'
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

::v-deep .light-navbar {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 1px 0 0 var(--hairline);
}
::v-deep .light-navbar .van-nav-bar__title {
  color: var(--ink);
  font-weight: 600;
}
::v-deep .light-navbar .van-icon {
  color: var(--ink) !important;
  font-size: 20px !important;
  padding: 6px;
}
::v-deep .light-navbar::after {
  background-color: transparent;
}

.req-card {
  margin: 12px 16px 0;
  padding: 16px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.req-biz {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
}
.card-body .row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: var(--ink-muted);
  line-height: 2;
}
.label {
  color: var(--ink-subtle);
}
.card-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.hint {
  font-size: 12px;
  color: var(--ink-subtle);
}
</style>
