<template>
  <div class="app-container">
    <!-- 页头统计 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F1F0FE;color:#8B5CF6">
            <i class="el-icon-s-order" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ total }}</div>
            <div class="stat-label">我发起的</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-time" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ processingCount }}</div>
            <div class="stat-label">审批中</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-circle-check" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ approvedCount }}</div>
            <div class="stat-label">已通过</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF0F0;color:#F56C6C">
            <i class="el-icon-circle-close" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ rejectedCount }}</div>
            <div class="stat-label">已驳回/撤销</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-card class="box-card filter-card">
      <el-button icon="el-icon-refresh" style="float:right" @click="getList">刷新</el-button>
    </el-card>

    <el-card class="box-card">
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column prop="title" label="审批标题" min-width="220" align="center" show-overflow-tooltip />
        <el-table-column prop="bizType" label="业务类型" width="130" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="bizTypeTag(row.bizType)" size="mini">{{ bizTypeText(row.bizType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="flowName" label="流程" width="140" align="center" show-overflow-tooltip />
        <el-table-column prop="currentNode" label="当前节点" width="130" align="center">
          <template slot-scope="{ row }">{{ row.currentNode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="approvalStatus" label="状态" width="110" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="statusTag(row.approvalStatus)" size="mini">{{ statusText(row.approvalStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="发起时间" width="170" align="center" />
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" icon="el-icon-view" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情弹窗 -->
    <el-dialog title="审批单详情" :visible.sync="detailVisible" width="560px" append-to-body>
      <el-descriptions :column="1" border v-if="detail.bill">
        <el-descriptions-item label="审批标题">{{ detail.bill.title }}</el-descriptions-item>
        <el-descriptions-item label="业务类型">{{ bizTypeText(detail.bill.bizType) }}</el-descriptions-item>
        <el-descriptions-item label="业务单号">{{ detail.bill.bizId }}</el-descriptions-item>
        <el-descriptions-item label="流程">{{ detail.bill.flowName }}（{{ detail.bill.flowCode }}）</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(detail.bill.approvalStatus)" size="mini">{{ statusText(detail.bill.approvalStatus) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ detail.bill.currentNode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ detail.bill.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
      <h4 style="margin:16px 0 8px">流程历史</h4>
      <el-timeline v-if="detail.history && detail.history.length">
        <el-timeline-item
          v-for="(h, idx) in detail.history"
          :key="idx"
          :timestamp="h.createTime || ''"
          placement="top"
        >
          <div class="his-item">
            <b>{{ h.nodeName || h.nodeCode }}</b>
            <span class="his-op">{{ h.handlerType || h.flowStatus || '' }}</span>
            <span class="his-op">{{ h.message || '' }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无流程历史" />
    </el-dialog>
  </div>
</template>

<script>
import { listMyStarted, getBillDetail } from '@/api/workflow/workflow'

const BIZ_TYPES = {
  out_stock: '备件出库',
  in_stock: '备件入库',
  workorder_accept: '工单验收',
  material_apply: '领料申请',
  scrap: '报废退役',
  fault_kb: '故障库发布',
  inspection_exception: '点检异常',
  notice_publish: '公告发布'
}
const STATUS_MAP = {
  processing: { text: '审批中', tag: 'warning' },
  approved: { text: '已通过', tag: 'success' },
  rejected: { text: '已驳回', tag: 'danger' },
  canceled: { text: '已撤销', tag: 'info' }
}

export default {
  name: 'WorkflowStarted',
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      detailVisible: false,
      detail: {}
    }
  },
  computed: {
    processingCount() { return this.list.filter(r => r.approvalStatus === 'processing').length },
    approvedCount() { return this.list.filter(r => r.approvalStatus === 'approved').length },
    rejectedCount() { return this.list.filter(r => ['rejected', 'canceled'].includes(r.approvalStatus)).length }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listMyStarted().then(data => {
        this.list = data || []
        this.total = this.list.length
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    bizTypeText(t) { return BIZ_TYPES[t] || t || '—' },
    bizTypeTag(t) {
      if (t === 'out_stock' || t === 'material_apply') return 'warning'
      if (t === 'in_stock') return 'success'
      return 'primary'
    },
    statusText(s) { return (STATUS_MAP[s] || {}).text || s },
    statusTag(s) { return (STATUS_MAP[s] || {}).tag || 'info' },
    showDetail(row) {
      getBillDetail(row.id).then(data => {
        this.detail = data || {}
        this.detailVisible = true
      })
    }
  }
}
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.filter-card { margin-bottom: 16px; }
.box-card { border-radius: 12px; }
.his-item { line-height: 1.6; }
.his-op { margin-left: 8px; color: #909399; font-size: 12px; }
</style>
