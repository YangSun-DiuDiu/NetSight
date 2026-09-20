<template>
  <div class="app-container">
    <!-- 搜索 -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query">
        <el-form-item label="计划名称">
          <el-input v-model="query.planName" placeholder="计划名称" clearable style="width: 180px" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 计划表格 -->
    <el-card shadow="never">
      <div slot="header" class="card-head">
        <span>点检计划</span>
        <el-button v-if="hasPerm('inspection:plan:add')" type="primary" icon="el-icon-plus" @click="openAdd">新增计划</el-button>
      </div>
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="计划名称" prop="planName" min-width="140" show-overflow-tooltip />
        <el-table-column label="周期" width="90" align="center">
          <template slot-scope="scope">
            <el-tag size="mini">{{ cycleText(scope.row.cycleType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="120" align="center">
          <template slot-scope="scope">
            {{ scope.row.targetType === 'device' ? '指定设备' : '按设备类型' }}
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="160" show-overflow-tooltip>
          <template slot-scope="scope">
            {{ scope.row.targetIds }}
          </template>
        </el-table-column>
        <el-table-column label="执行人" prop="assigneeName" width="100" align="center" />
        <el-table-column label="生效期" width="180" align="center">
          <template slot-scope="scope">
            {{ scope.row.startDate || '-' }} ~ {{ scope.row.endDate || '长期' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'info'" size="mini">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button v-if="hasPerm('inspection:plan:edit')" type="success" size="mini" icon="el-icon-s-promotion" @click="handleGenerate(scope.row)">生成任务</el-button>
            <el-button v-if="hasPerm('inspection:plan:edit')" type="primary" size="mini" icon="el-icon-edit" @click="openEdit(scope.row)">修改</el-button>
            <el-button v-if="hasPerm('inspection:plan:remove')" type="danger" size="mini" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="getList" />
    </el-card>

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="form.id ? '修改点检计划' : '新增点检计划'" :visible.sync="dialogVisible" width="680px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="计划名称" prop="planName">
          <el-input v-model="form.planName" placeholder="如：网络设备每日点检" />
        </el-form-item>
        <el-form-item label="周期类型">
          <el-radio-group v-model="form.cycleType">
            <el-radio-button label="daily">每日</el-radio-button>
            <el-radio-button label="weekly">每周一</el-radio-button>
            <el-radio-button label="monthly">每月1日</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="对象类型">
          <el-radio-group v-model="form.targetType">
            <el-radio-button label="device">指定设备</el-radio-button>
            <el-radio-button label="device_type">按设备类型</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标" prop="targetIds">
          <el-input v-model="form.targetIds" :placeholder="form.targetType === 'device' ? '设备ID列表（JSON数组），如 [1,2,3]' : '设备类型列表（JSON数组），如 network,camera'" />
          <div class="tip">设备类型可选：network 网络设备 / camera 摄像头 / nvr NVR / door_controller 门禁</div>
        </el-form-item>
        <el-form-item label="执行人">
          <el-row :gutter="8">
            <el-col :span="12"><el-input v-model="form.assigneeName" placeholder="执行人姓名" /></el-col>
            <el-col :span="12"><el-input v-model="form.assigneeId" placeholder="执行人用户ID" /></el-col>
          </el-row>
        </el-form-item>
        <el-form-item label="生效期">
          <el-date-picker v-model="form.dateRange" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期（空=长期）" value-format="yyyy-MM-dd" style="width: 100%" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="计划说明" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listInspectionPlan, addInspectionPlan, updateInspectionPlan, delInspectionPlan, generateInspectionTask } from '@/api/inspection/inspection'
import Pagination from '@/components/Pagination'

export default {
  name: 'InspectionPlan',
  components: { Pagination },
  data() {
    return {
      loading: false,
      saving: false,
      list: [],
      total: 0,
      query: { pageNum: 1, pageSize: 10, planName: '', status: null },
      dialogVisible: false,
      form: {},
      rules: {
        planName: [{ required: true, message: '计划名称不能为空', trigger: 'blur' }],
        targetIds: [{ required: true, message: '目标不能为空', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listInspectionPlan(this.query).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() { this.query.pageNum = 1; this.getList() },
    resetQuery() {
      this.query = { pageNum: 1, pageSize: 10, planName: '', status: null }
      this.handleQuery()
    },
    openAdd() {
      this.form = { cycleType: 'daily', targetType: 'device', status: 1, dateRange: null, assigneeId: '', assigneeName: '' }
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    openEdit(row) {
      this.form = {
        id: row.id, planName: row.planName, cycleType: row.cycleType, targetType: row.targetType,
        targetIds: row.targetIds, assigneeId: row.assigneeId, assigneeName: row.assigneeName,
        status: row.status, remark: row.remark,
        dateRange: row.startDate && row.endDate ? [row.startDate, row.endDate] : (row.startDate ? [row.startDate, null] : null)
      }
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleSave() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        const payload = { ...this.form }
        delete payload.dateRange
        if (this.form.dateRange && this.form.dateRange[0]) {
          payload.startDate = this.form.dateRange[0]
          payload.endDate = this.form.dateRange[1] || null
        } else {
          payload.startDate = null
          payload.endDate = null
        }
        if (this.form.assigneeId === '') payload.assigneeId = null
        this.saving = true
        const req = this.form.id ? updateInspectionPlan(payload) : addInspectionPlan(payload)
        req.then(() => {
          this.saving = false
          this.dialogVisible = false
          this.$message.success('保存成功')
          this.getList()
        }).catch(() => { this.saving = false })
      })
    },
    handleGenerate(row) {
      this.$prompt('输入任务日期（yyyy-MM-dd，空=今天）', '生成点检任务', {
        inputPattern: /^$|^\d{4}-\d{2}-\d{2}$/,
        inputErrorMessage: '日期格式不正确',
        inputPlaceholder: '如 2026-09-16'
      }).then(({ value }) => {
        generateInspectionTask(row.id, value).then(num => {
          this.$message.success('已生成 ' + num + ' 条任务（重复日期自动跳过）')
          this.getList()
        })
      }).catch(() => {})
    },
    handleDelete(row) {
      this.$confirm('删除计划将不影响已生成任务，确定删除「' + row.planName + '」？', '提示', { type: 'warning' }).then(() => {
        delInspectionPlan(row.id).then(() => {
          this.$message.success('删除成功')
          this.getList()
        })
      }).catch(() => {})
    },
    cycleText(c) {
      return { daily: '每日', weekly: '每周一', monthly: '每月1日' }[c] || c
    },
    hasPerm(key) {
      const perms = this.$store.getters.permissions || []
      return perms.includes(key) || (this.$store.getters.roles || []).includes('super_admin')
    }
  }
}
</script>

<style scoped>
.search-card { margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.tip { font-size: 12px; color: #9CA3AF; line-height: 1.6; }
</style>
