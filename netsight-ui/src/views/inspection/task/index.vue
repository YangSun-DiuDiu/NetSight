<template>
  <div class="app-container">
    <!-- 统计卡 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F3F4F6;color:#6B7280">
            <i class="el-icon-files" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.total }}</div>
            <div class="stat-label">任务总数</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-time" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.pending }}</div>
            <div class="stat-label">待执行</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-loading" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.running }}</div>
            <div class="stat-label">执行中</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-circle-check" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.done }}</div>
            <div class="stat-label">已完成</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FEE2E2;color:#EF4444">
            <i class="el-icon-warning-outline" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.overdue }}</div>
            <div class="stat-label">已逾期</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="4">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F97316">
            <i class="el-icon-error" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.abnormal }}</div>
            <div class="stat-label">含异常</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 搜索 -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query">
        <el-form-item label="目标名称">
          <el-input v-model="query.targetName" placeholder="设备名称" clearable style="width: 180px" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="待执行" :value="0" />
            <el-option label="执行中" :value="1" />
            <el-option label="已完成" :value="2" />
            <el-option label="已逾期" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="我的任务">
          <el-switch v-model="query.my" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务表格 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="任务编号" prop="taskNo" width="250" show-overflow-tooltip  align="center"/>
        <el-table-column label="计划" prop="planName" width="250" show-overflow-tooltip  align="center"/>
        <el-table-column label="目标设备" prop="targetName" min-width="100" show-overflow-tooltip  align="center"/>
        <el-table-column label="位置" prop="targetLocation" width="150" show-overflow-tooltip  align="center"/>
        <el-table-column label="执行人" prop="assigneeName" width="150" align="center" />
        <el-table-column label="计划日期" prop="planDate" width="150" align="center" />
        <el-table-column label="状态" width="150" align="center">
          <template slot-scope="scope">
            <el-tag size="mini" :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="异常项" width="150" align="center">
          <template slot-scope="scope">
            <el-tag v-if="scope.row.abnormalCount > 0" type="danger" size="mini">{{ scope.row.abnormalCount }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="180" class-name="small-padding fixed-width" fixed="right">
          <template slot-scope="scope">
            <el-button v-if="scope.row.status === 0 && hasPerm('inspection:task:execute')" type="text" size="mini" icon="el-icon-video-play" @click="handleStart(scope.row)">开始</el-button>
            <el-button v-if="(scope.row.status === 0 || scope.row.status === 1) && hasPerm('inspection:task:execute')" type="text" size="mini" icon="el-icon-s-check" @click="handleExecute(scope.row)">执行</el-button>
            <el-button type="text" size="mini" icon="el-icon-view" @click="handleDetail(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="getList" />
    </el-card>

    <!-- 执行弹窗 -->
    <el-dialog :title="'执行点检 - ' + (currentTask ? currentTask.targetName : '')" :visible.sync="execVisible" width="720px" append-to-body :close-on-click-modal="false">
      <template v-if="currentTask">
      <el-descriptions :column="2" size="small" border class="exec-info">
        <el-descriptions-item label="任务编号">{{ currentTask.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="计划">{{ currentTask.planName }}</el-descriptions-item>
        <el-descriptions-item label="目标设备">{{ currentTask.targetName }}</el-descriptions-item>
        <el-descriptions-item label="位置">{{ currentTask.targetLocation }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="items.length === 0" type="warning" :closable="false" show-icon title="暂无启用的点检项，请先在「点检项库」中维护" style="margin-top: 12px" />
      <div v-for="(item, idx) in items" :key="item.id" class="exec-item">
        <div class="exec-item-head">
          <span class="exec-item-name">{{ idx + 1 }}. {{ item.itemName }}</span>
          <el-tag size="mini" type="info">{{ item.resultType === 'text' ? '文本填写' : '勾选' }}</el-tag>
        </div>
        <div class="exec-item-desc">
          <span v-if="item.checkContent">检查内容：{{ item.checkContent }}</span>
          <span v-if="item.checkStandard" class="muted">｜标准：{{ item.checkStandard }}</span>
        </div>
        <div class="exec-item-ops">
          <template v-if="item.resultType === 'text'">
            <el-input v-model="item.textResult" placeholder="填写检查结果数值/文本" style="width: 320px" />
          </template>
          <el-radio-group v-model="item.result" :disabled="item.resultType === 'text'">
            <el-radio :label="0">正常</el-radio>
            <el-radio :label="1">异常</el-radio>
          </el-radio-group>
          <el-input v-model="item.remark" placeholder="备注（异常原因）" style="width: 220px" />
        </div>
      </div>
      <el-form label-width="90px" style="margin-top: 12px">
        <el-form-item label="异常报修">
          <el-switch v-model="repairMode" active-text="异常项自动生成报修工单" />
        </el-form-item>
        <el-form-item label="任务备注">
          <el-input v-model="execRemark" type="textarea" :rows="2" placeholder="本次点检总体备注" />
        </el-form-item>
      </el-form>
      </template>
      <div slot="footer">
        <el-button @click="execVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">提交点检</el-button>
      </div>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog :title="'点检详情 - ' + (currentTask ? currentTask.targetName : '')" :visible.sync="detailVisible" width="760px" append-to-body :close-on-click-modal="false">
      <template v-if="currentTask">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="任务编号">{{ currentTask.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="计划">{{ currentTask.planName }}</el-descriptions-item>
        <el-descriptions-item label="目标设备">{{ currentTask.targetName }}</el-descriptions-item>
        <el-descriptions-item label="位置">{{ currentTask.targetLocation }}</el-descriptions-item>
        <el-descriptions-item label="执行人">{{ currentTask.assigneeName }}</el-descriptions-item>
        <el-descriptions-item label="计划日期">{{ currentTask.planDate }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="mini" :type="statusType(currentTask.status)">{{ statusText(currentTask.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ currentTask.finishTime || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="detailRecords.length > 0" :data="detailRecords" border size="mini" style="margin-top: 12px">
        <el-table-column label="点检项" prop="itemName" min-width="120" />
        <el-table-column label="结果" width="80" align="center">
          <template slot-scope="scope">
            <el-tag :type="scope.row.result === 1 ? 'danger' : 'success'" size="mini">{{ scope.row.result === 1 ? '异常' : '正常' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文本结果" prop="textResult" min-width="100" />
        <el-table-column label="备注" prop="remark" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作人" prop="operatorName" width="90" align="center" />
        <el-table-column label="关联工单" width="90" align="center">
          <template slot-scope="scope">
            <el-tag v-if="scope.row.orderId" type="warning" size="mini">{{ scope.row.orderId }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无点检记录" style="margin-top: 12px" />
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { listInspectionTask, getInspectionTask, startInspectionTask, submitInspectionTask, getInspectionTaskStats, listEnabledItem } from '@/api/inspection/inspection'
import Pagination from '@/components/Pagination'

export default {
  name: 'InspectionTask',
  components: { Pagination },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      query: { pageNum: 1, pageSize: 10, targetName: '', status: null, my: false },
      stats: { total: 0, pending: 0, running: 0, done: 0, overdue: 0, abnormal: 0 },
      execVisible: false,
      detailVisible: false,
      submitting: false,
      currentTask: null,
      items: [],
      repairMode: false,
      execRemark: '',
      detailRecords: []
    }
  },
  created() {
    this.getList()
    this.getStats()
  },
  methods: {
    getList() {
      this.loading = true
      listInspectionTask(this.query).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    getStats() {
      getInspectionTaskStats().then(res => { this.stats = res || {} })
    },
    handleQuery() { this.query.pageNum = 1; this.getList(); this.getStats() },
    resetQuery() {
      this.query = { pageNum: 1, pageSize: 10, targetName: '', status: null, my: false }
      this.handleQuery()
    },
    handleStart(row) {
      this.$confirm('确定开始执行该点检任务？', '提示', { type: 'warning' }).then(() => {
        startInspectionTask(row.id).then(() => {
          this.$message.success('已开始执行')
          this.getList()
        })
      }).catch(() => {})
    },
    handleExecute(row) {
      this.currentTask = row
      this.repairMode = false
      this.execRemark = ''
      listEnabledItem().then(res => {
        this.items = (res || []).map(it => ({ ...it, result: 0, textResult: '', remark: '' }))
        this.execVisible = true
      })
    },
    handleSubmit() {
      const payload = {
        taskId: this.currentTask.id,
        repairMode: this.repairMode ? 1 : 0,
        remark: this.execRemark,
        items: this.items.map(it => ({
          itemId: it.id, itemName: it.itemName, checkContent: it.checkContent,
          checkStandard: it.checkStandard, resultType: it.resultType,
          result: it.result, textResult: it.textResult, remark: it.remark
        }))
      }
      this.submitting = true
      submitInspectionTask(payload).then(res => {
        this.submitting = false
        this.execVisible = false
        const abnormal = res.abnormalCount || 0
        let msg = '点检提交成功'
        if (abnormal > 0) {
          msg += '，发现 ' + abnormal + ' 项异常' + (res.orderId ? '，已生成报修工单 #' + res.orderId : '')
        }
        this.$message.success(msg)
        this.getList(); this.getStats()
      }).catch(() => { this.submitting = false })
    },
    handleDetail(row) {
      this.currentTask = row
      this.detailRecords = []
      getInspectionTask(row.id).then(res => {
        this.currentTask = res.task || row
        this.detailRecords = res.records || []
        this.detailVisible = true
      })
    },
    statusText(s) {
      return ['待执行', '执行中', '已完成', '已逾期'][s] || '-'
    },
    statusType(s) {
      return ['warning', 'primary', 'success', 'danger'][s] || 'info'
    }
  }
}
</script>

<style scoped>
.search-card { margin-bottom: 16px; }
.exec-info { margin-bottom: 4px; }
.exec-item { border: 1px solid #E5E7EB; border-radius: 8px; padding: 10px 12px; margin-top: 10px; }
.exec-item-head { display: flex; align-items: center; gap: 8px; }
.exec-item-name { font-weight: 600; color: #1F2937; }
.exec-item-desc { font-size: 12px; color: #6B7280; margin: 6px 0; }
.muted { color: #9CA3AF; }
.exec-item-ops { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
</style>
