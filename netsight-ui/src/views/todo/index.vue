<template>
  <div class="app-container todo-page">
    <!-- 统计卡（只统计审批数据） -->
    <div class="stat-row flex-stats">
      <div class="stat-card">
        <div class="stat-icon" style="background:#F1F0FE;color:#8B5CF6">
          <i class="el-icon-s-check" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.workflowTodo }}</div>
          <div class="stat-label">待我审批</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
          <i class="el-icon-document-checked" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.doneTotal }}</div>
          <div class="stat-label">历史已办</div>
        </div>
      </div>
    </div>

    <!-- 类型筛选：仅保留审批待办与历史已办 -->
    <el-card class="box-card filter-card">
      <el-radio-group v-model="query.type" @change="handleSearch" class="type-group">
        <el-radio-button label="workflow">待办</el-radio-button>
        <el-radio-button label="done">历史已办</el-radio-button>
      </el-radio-group>
      <el-button type="primary" icon="el-icon-refresh" style="float:right" @click="handleRefresh">刷新</el-button>
    </el-card>

    <el-card class="box-card">
      <!-- 历史已办 -->
      <el-table key="doneTable" v-if="query.type === 'done'" v-loading="loading" :data="list" border stripe>
        <el-table-column prop="bizId" label="业务编号" width="150" show-overflow-tooltip align="center" />
        <el-table-column prop="title" label="审批标题" min-width="160" show-overflow-tooltip align="center" />
        <el-table-column label="流程" width="110" show-overflow-tooltip align="center">
          <template slot-scope="{ row }">{{ row.flowName || row.flowCode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="applyUserName" label="发起人" width="100" align="center" />
        <el-table-column label="我的结果" width="80" align="center">
          <template slot-scope="{ row }">
            <status-tag :text="row.actionText || row.skipType || '—'"
                        :type="row.actionTag || (row.skipType === 'PASS' ? 'success' : (row.skipType === 'REJECT' ? 'danger' : 'info'))" />
          </template>
        </el-table-column>
        <el-table-column prop="myComment" label="我的意见" min-width="130" show-overflow-tooltip align="center">
          <template slot-scope="{ row }">{{ row.myComment || '—' }}</template>
        </el-table-column>
        <el-table-column label="单据当前状态" width="110" align="center">
          <template slot-scope="{ row }">
            <status-tag :text="row.currentStatusText || billStatusText(row.currentBillStatus)"
                        :type="row.currentStatusTag || billStatusTag(row.currentBillStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="审批时间" width="165" align="center">
          <template slot-scope="{ row }">{{ formatTime(row.approveTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="80" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" icon="el-icon-view" @click="openDetailReadOnly(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 待办（仅审批） -->
      <el-table key="todoTable" v-else v-loading="loading" :data="list" border stripe>
        <el-table-column prop="bizNo" label="业务编号" width="150" show-overflow-tooltip align="center" />
        <el-table-column prop="title" label="审批标题" min-width="180" show-overflow-tooltip align="center" />
        <el-table-column label="流程" width="120" show-overflow-tooltip align="center">
          <template slot-scope="{ row }">{{ row.flowName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="applyUserName" label="发起人" width="100" align="center" />
        <el-table-column label="当前节点" width="130" show-overflow-tooltip align="center">
          <template slot-scope="{ row }">{{ row.nodeName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template slot-scope="{ row }">
            <status-tag :text="row.statusText || '审批中'" :type="row.statusTag || 'warning'" />
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="提交时间" width="165" align="center" />
        <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" icon="el-icon-s-check" @click="openApprove(row)">去审批</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize"
                  @pagination="handlePageChange" />
    </el-card>

    <!-- 审批弹窗 -->
    <el-dialog title="审批单详情" :visible.sync="approveVisible" width="640px" append-to-body>
      <el-descriptions :column="1" border v-if="approveDetail.bill">
        <el-descriptions-item label="审批标题">{{ approveDetail.bill.title }}</el-descriptions-item>
        <el-descriptions-item label="业务单号">{{ approveDetail.bill.bizId }}</el-descriptions-item>
        <el-descriptions-item label="流程">{{ approveDetail.bill.flowName || approveDetail.bill.flowCode }}（{{ approveDetail.bill.flowCode }}）</el-descriptions-item>
        <el-descriptions-item label="发起人">{{ approveDetail.bill.applyUserName }}</el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ approveDetail.bill.currentNode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ approveDetail.bill.remark || '—' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 审批轨迹 -->
      <div v-if="approveDetail.history && approveDetail.history.length" style="margin-top:16px">
        <div style="font-weight:600;margin-bottom:8px">审批轨迹</div>
        <el-timeline>
          <el-timeline-item v-for="(h, idx) in approveDetail.history" :key="idx"
                            :timestamp="formatTime(h.createTime)" placement="top">
            <status-tag :text="h.actionText || h.nodeName" :type="h.actionTag || 'info'" />
            <span style="margin-left:8px;font-weight:600">{{ h.nodeName }}</span>
            <div style="margin-top:4px;font-size:13px;color:#666">
              处理人：{{ h.approver || '—' }}
              <span v-if="h.message">　意见：{{ h.message }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>

      <!-- 待办模式才显示审批意见输入框 + 通过/驳回按钮 -->
      <template v-if="!readOnlyMode">
        <el-input
          v-model="approveComment"
          type="textarea"
          :rows="3"
          placeholder="请输入审批意见（驳回时必填）"
          style="margin-top:12px"
        />
        <div slot="footer">
          <el-button @click="approveVisible = false">取消</el-button>
          <el-button type="danger" :loading="approveLoading" @click="handleApprove(false)">驳回</el-button>
          <el-button type="primary" :loading="approveLoading" @click="handleApprove(true)">通过</el-button>
        </div>
      </template>
      <div v-else slot="footer">
        <el-button @click="approveVisible = false">关闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMyTodo, listMyDone, getBillDetail, approveFlow } from '@/api/workflow/workflow'
import Pagination from '@/components/Pagination'
import StatusTag from '@/components/StatusTag'
import { billStatusText as statusTextOf, billStatusTag as statusTagOf } from '@/utils/workflowStatus'

export default {
  name: 'Todo',
  components: { Pagination, StatusTag },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      // 待办全量缓存（myTodo 接口暂未分页，前端切片展示）
      todoAll: [],
      stats: { workflowTodo: 0, doneTotal: 0 },
      query: { type: 'workflow', pageNum: 1, pageSize: 10 },
      approveVisible: false,
      approveLoading: false,
      approveComment: '',
      approveDetail: {},
      approveBillId: null,
      readOnlyMode: false
    }
  },
  created() {
    this.getStats()
    this.getList()
  },
  methods: {
    getStats() {
      // 待我审批：引擎待办数组长度
      listMyTodo().then(data => {
        this.stats.workflowTodo = (data || []).length
      }).catch(() => {})
      // 历史已办：取分页总数（pageSize=1 只取 total，避免拉全量）
      listMyDone({ pageNum: 1, pageSize: 1 }).then(data => {
        this.stats.doneTotal = (data && data.total) || 0
      }).catch(() => {})
    },
    getList() {
      this.loading = true
      if (this.query.type === 'done') {
        listMyDone({ pageNum: this.query.pageNum, pageSize: this.query.pageSize }).then(data => {
          this.list = (data && data.rows) || []
          this.total = (data && data.total) || 0
          this.loading = false
        }).catch(() => { this.loading = false })
        return
      }
      // 待办：myTodo 返回全量，前端按页码切片
      listMyTodo().then(data => {
        this.todoAll = (data || []).map(t => ({
          bizNo: t.businessId || '',
          title: t.title || t.flowName || '',
          flowName: t.flowName || '',
          nodeName: t.nodeName || '',
          applyUserName: t.applyUserName || '',
          statusText: t.statusText || '审批中',
          statusTag: t.statusTag || 'warning',
          createTime: t.createTime || '',
          billId: t.billId,
          taskId: t.taskId
        }))
        this.total = this.todoAll.length
        // 总待办统计同步
        this.stats.workflowTodo = this.total
        this.sliceTodoPage()
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    sliceTodoPage() {
      const from = (this.query.pageNum - 1) * this.query.pageSize
      this.list = this.todoAll.slice(from, from + this.query.pageSize)
    },
    handleSearch() {
      this.query.pageNum = 1
      this.getList()
    },
    handlePageChange() {
      if (this.query.type === 'done') {
        this.getList()
      } else {
        this.sliceTodoPage()
      }
    },
    handleRefresh() {
      this.getStats()
      this.getList()
    },
    billStatusText(code) {
      return statusTextOf(code)
    },
    billStatusTag(code) {
      return statusTagOf(code)
    },
    formatTime(value) {
      return value ? String(value).replace('T', ' ') : '—'
    },
    openApprove(row) {
      this.readOnlyMode = false
      this.approveComment = ''
      this.approveBillId = row.billId
      getBillDetail(row.billId).then(data => {
        this.approveDetail = data || {}
        this.approveVisible = true
      })
    },
    openDetailReadOnly(row) {
      this.readOnlyMode = true
      this.approveComment = ''
      this.approveBillId = row.billId
      getBillDetail(row.billId).then(data => {
        this.approveDetail = data || {}
        this.approveVisible = true
      })
    },
    handleApprove(pass) {
      if (!pass && !this.approveComment.trim()) {
        this.$message.warning('驳回时请填写审批意见')
        return
      }
      this.approveLoading = true
      approveFlow({ billId: this.approveBillId, pass, comment: this.approveComment }).then(() => {
        this.$message.success(pass ? '已通过' : '已驳回')
        this.approveVisible = false
        this.approveLoading = false
        this.getStats()
        this.getList()
      }).catch(() => { this.approveLoading = false })
    }
  }
}
</script>

<style scoped>
.flex-stats {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}
.flex-stats .stat-card {
  flex: 1;
  min-width: 0;
}
.filter-card { margin-bottom: 16px; }
.type-group .el-radio-button__inner { border-radius: 6px !important; }
.box-card { border-radius: 12px; }
</style>
