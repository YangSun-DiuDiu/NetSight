<template>
  <div class="app-container todo-page">
    <!-- 统计卡（5 卡 flex 布局） -->
    <div class="stat-row flex-stats">
      <div class="stat-card">
        <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
          <i class="el-icon-document" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.workOrderPending }}</div>
          <div class="stat-label">待处理工单</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
          <i class="el-icon-user" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.workOrderMine }}</div>
          <div class="stat-label">我的维修工单</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
          <i class="el-icon-date" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.inspectionTodo }}</div>
          <div class="stat-label">待执行点检</div>
        </div>
      </div>
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
        <div class="stat-icon" style="background:#EEF5FF;color:#409EFF">
          <i class="el-icon-s-order" />
        </div>
        <div class="stat-info">
          <div class="stat-num">{{ stats.total }}</div>
          <div class="stat-label">总待办</div>
        </div>
      </div>
    </div>

    <!-- 类型筛选 -->
    <el-card class="box-card filter-card">
      <el-radio-group v-model="query.type" @change="handleSearch" class="type-group">
        <el-radio-button label="all">全部待办</el-radio-button>
        <el-radio-button label="work_order">工单待办</el-radio-button>
        <el-radio-button label="inspection">点检待办</el-radio-button>
        <el-radio-button label="workflow">审批待办</el-radio-button>
        <el-radio-button label="done">历史已办</el-radio-button>
      </el-radio-group>
      <el-button type="primary" icon="el-icon-refresh" style="float:right" @click="handleRefresh">刷新</el-button>
    </el-card>

    <!-- 待办列表 -->
    <el-card class="box-card">
      <el-table v-loading="loading" :data="list" border stripe>
        <!-- 历史已办列 -->
        <template v-if="query.type === 'done'">
          <el-table-column prop="bizId" label="业务编号" width="160" show-overflow-tooltip align="center" />
          <el-table-column prop="title" label="审批标题" min-width="220" show-overflow-tooltip align="center" />
          <el-table-column prop="flowName" label="流程" width="140" show-overflow-tooltip align="center" />
          <el-table-column prop="applyUserName" label="发起人" width="110" align="center" />
          <el-table-column label="我的结果" width="110" align="center">
            <template slot-scope="{ row }">
              <el-tag v-if="row.skipType === 'PASS'" type="success" size="mini">通过</el-tag>
              <el-tag v-else-if="row.skipType === 'REJECT'" type="danger" size="mini">驳回</el-tag>
              <el-tag v-else size="mini">{{ row.skipType || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="myComment" label="我的意见" min-width="160" show-overflow-tooltip align="center">
            <template slot-scope="{ row }">{{ row.myComment || '—' }}</template>
          </el-table-column>
          <el-table-column label="单据当前状态" width="120" align="center">
            <template slot-scope="{ row }">
              <el-tag v-if="row.currentBillStatus === 'approved'" type="success" size="mini">已通过</el-tag>
              <el-tag v-else-if="row.currentBillStatus === 'rejected'" type="danger" size="mini">已驳回</el-tag>
              <el-tag v-else-if="row.currentBillStatus === 'processing'" type="warning" size="mini">审批中</el-tag>
              <el-tag v-else-if="row.currentBillStatus === 'canceled'" type="info" size="mini">已撤销</el-tag>
              <el-tag v-else size="mini">{{ row.currentBillStatus || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="approveTime" label="审批时间" width="170" align="center" />
          <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width">
            <template slot-scope="{ row }">
              <el-button type="text" size="mini" icon="el-icon-view" @click="openDetailReadOnly(row)">查看</el-button>
            </template>
          </el-table-column>
        </template>

        <!-- 待办列 -->
        <template v-else>
          <el-table-column label="待办类型" width="110" align="center">
            <template slot-scope="{ row }">
              <el-tag v-if="row.bizType === 'work_order'" type="warning" effect="light">工单</el-tag>
              <el-tag v-else-if="row.bizType === 'workflow'" type="primary" effect="light">审批</el-tag>
              <el-tag v-else type="success" effect="light">点检</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="bizNo" label="业务编号" width="160" show-overflow-tooltip align="center" />
          <el-table-column prop="title" label="待办事项" min-width="200" show-overflow-tooltip align="center" />
          <el-table-column prop="deviceName" label="关联设备" min-width="150" show-overflow-tooltip align="center">
            <template slot-scope="{ row }">{{ row.deviceName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip align="center" />
          <el-table-column prop="statusText" label="状态" width="110" align="center">
            <template slot-scope="{ row }">
              <el-tag :type="row.statusTag || (row.statusText === '待派单' ? 'danger' : (row.statusText === '待执行' ? 'primary' : 'warning'))"
                      size="mini">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
          <el-table-column label="操作" align="center" width="140" class-name="small-padding fixed-width">
            <template slot-scope="{ row }">
              <el-button v-if="row.bizType === 'workflow'" type="text" size="mini"
                         icon="el-icon-s-check" @click="openApprove(row)">去审批</el-button>
              <el-button v-else-if="row.bizType === 'work_order'" type="text" size="mini"
                         icon="el-icon-s-order" @click="goWorkOrder">去处理</el-button>
              <el-button v-else type="text" size="mini"
                         icon="el-icon-s-check" @click="goInspection">去处理</el-button>
            </template>
          </el-table-column>
        </template>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize"
                  @pagination="getList" />
    </el-card>

    <!-- 审批弹窗 -->
    <el-dialog title="审批单详情" :visible.sync="approveVisible" width="640px" append-to-body>
      <el-descriptions :column="1" border v-if="approveDetail.bill">
        <el-descriptions-item label="审批标题">{{ approveDetail.bill.title }}</el-descriptions-item>
        <el-descriptions-item label="业务单号">{{ approveDetail.bill.bizId }}</el-descriptions-item>
        <el-descriptions-item label="流程">{{ approveDetail.bill.flowName }}（{{ approveDetail.bill.flowCode }}）</el-descriptions-item>
        <el-descriptions-item label="发起人">{{ approveDetail.bill.applyUserName }}</el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ approveDetail.bill.currentNode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ approveDetail.bill.remark || '—' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 流程轨迹 -->
      <div v-if="approveDetail.history && approveDetail.history.length" style="margin-top:16px">
        <div style="font-weight:600;margin-bottom:8px">审批轨迹</div>
        <el-timeline>
          <el-timeline-item v-for="(h, idx) in approveDetail.history" :key="idx"
                            :timestamp="h.createTime" placement="top">
            <el-tag size="mini" :type="h.skipType === 'PASS' ? 'success' : (h.skipType === 'REJECT' ? 'danger' : 'info')">
              {{ h.nodeName }}
            </el-tag>
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
import { getTodoStats, listTodo } from '@/api/todo/todo'
import { listMyTodo, listMyDone, getBillDetail, approveFlow } from '@/api/workflow/workflow'
import Pagination from '@/components/Pagination'

const FLOW_STATUS_TEXT = {
  TOBESUBMIT: '待提交', APPROVAL: '审批中', PASS: '已通过', REJECT: '已驳回',
  TERMINATE: '已终止', CANCEL: '已撤销', RETRIEVE: '已撤回', FINISHED: '已完成'
}

export default {
  name: 'Todo',
  components: { Pagination },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      stats: { workOrderPending: 0, workOrderMine: 0, inspectionTodo: 0, workflowTodo: 0, total: 0 },
      query: { type: 'all', pageNum: 1, pageSize: 10 },
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
      getTodoStats().then(data => {
        this.stats = Object.assign({}, this.stats, data || {})
      })
      // 审批待办数量单独取（引擎层接口）
      listMyTodo().then(data => {
        this.stats.workflowTodo = (data || []).length
      }).catch(() => {})
    },
    getList() {
      this.loading = true
      if (this.query.type === 'done') {
        // 历史已办
        listMyDone({ pageNum: this.query.pageNum, pageSize: this.query.pageSize }).then(data => {
          this.list = (data && data.rows) || []
          this.total = (data && data.total) || 0
          this.loading = false
        }).catch(() => { this.loading = false })
        return
      }
      if (this.query.type === 'workflow') {
        listMyTodo().then(data => {
          this.list = (data || []).map(t => ({
            bizType: 'workflow',
            bizNo: t.businessId || '',
            title: t.title || t.flowName || '',
            deviceName: '',
            source: t.flowName || '',
            statusText: FLOW_STATUS_TEXT[t.flowStatus] || t.flowStatus || '审批中',
            statusTag: t.flowStatus === 'APPROVAL' ? 'primary' : 'warning',
            createTime: t.createTime || '',
            billId: t.billId,
            taskId: t.taskId
          }))
          this.total = this.list.length
          this.loading = false
        }).catch(() => { this.loading = false })
      } else {
        listTodo(this.query).then(data => {
          this.list = data.rows || []
          this.total = data.total || 0
          this.loading = false
        }).catch(() => { this.loading = false })
      }
    },
    handleSearch() {
      this.query.pageNum = 1
      this.getList()
    },
    handleRefresh() {
      this.getStats()
      this.getList()
    },
    goWorkOrder() {
      this.$router.push('/workorder/order')
    },
    goInspection() {
      this.$router.push('/inspection/task')
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
