<template>
  <div class="app-container">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-notebook-2" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.total }}</div>
            <div class="stat-label">知识总数</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-circle-check" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.enabled }}</div>
            <div class="stat-label">已启用</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-circle-close" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.disabled }}</div>
            <div class="stat-label">已停用</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F1F0FE;color:#8B5CF6">
            <i class="el-icon-view" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.viewCount }}</div>
            <div class="stat-label">累计浏览</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 搜索区 -->
    <el-card shadow="never" class="search-card">
      <el-form :model="query" inline @submit.native.prevent>
        <el-form-item label="标题">
          <el-input v-model="query.title" placeholder="输入标题/关键词" clearable style="width:180px" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="query.category" placeholder="全部分类" clearable style="width:140px">
            <el-option v-for="c in options.categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="query.deviceType" placeholder="全部类型" clearable style="width:140px">
            <el-option v-for="t in options.deviceTypes" :key="t" :label="deviceTypeLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:110px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
          <el-button v-if="hasPerm('knowledge:fault:add')" type="primary" plain icon="el-icon-plus" @click="handleAdd">新增知识</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 表格 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" border>
        <el-table-column label="标题" prop="title" min-width="220" show-overflow-tooltip />
        <el-table-column label="分类" prop="category" width="100">
          <template slot-scope="{ row }">
            <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备类型" prop="deviceType" width="110">
          <template slot-scope="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ deviceTypeLabel(row.deviceType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="品牌" prop="brand" width="110">
          <template slot-scope="{ row }">
            <span>{{ row.brand || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="参考级别" width="90" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="severityType(row.severityRef)" size="small">{{ severityLabel(row.severityRef) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="浏览" prop="viewCount" width="80" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建人" prop="createBy" width="110" />
        <el-table-column label="操作" width="180" align="center">
          <template slot-scope="{ row }">
            <el-button v-if="hasPerm('knowledge:fault:view')" type="text" size="mini" icon="el-icon-view" @click="handleView(row)">查看</el-button>
            <el-button v-if="hasPerm('knowledge:fault:edit')" type="text" size="mini" icon="el-icon-edit" @click="handleEdit(row)">修改</el-button>
            <el-button v-if="hasPerm('knowledge:fault:remove')" type="text" size="mini" icon="el-icon-delete" class="danger-text" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="getList" />
    </el-card>

    <!-- 新增 / 修改弹窗 -->
    <el-dialog :title="form.id ? '修改故障知识' : '新增故障知识'" :visible.sync="dialogVisible" width="720px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-row :gutter="12">
          <el-col :span="24">
            <el-form-item label="标题" prop="title">
              <el-input v-model="form.title" placeholder="如：设备离线排查指南" maxlength="200" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分类" prop="category">
              <el-select v-model="form.category" placeholder="选择分类" style="width:100%">
                <el-option v-for="c in options.categories" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备类型" prop="deviceType">
              <el-select v-model="form.deviceType" placeholder="适用设备类型" style="width:100%">
                <el-option v-for="t in options.deviceTypes" :key="t" :label="deviceTypeLabel(t)" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="适用品牌">
              <el-input v-model="form.brand" placeholder="空=不限，多个用逗号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="适用型号">
              <el-input v-model="form.model" placeholder="空=不限" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="关键词">
              <el-input v-model="form.keywords" placeholder="逗号分隔，用于检索匹配，如：离线,断网" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider content-position="left">故障详情</el-divider>
        <el-form-item label="故障现象" prop="symptom">
          <el-input v-model="form.symptom" type="textarea" :rows="3" placeholder="描述故障现象，供检索定位" />
        </el-form-item>
        <el-form-item label="可能原因" prop="cause">
          <el-input v-model="form.cause" type="textarea" :rows="3" placeholder="逐条列出可能原因" />
        </el-form-item>
        <el-form-item label="处理步骤" prop="solution">
          <el-input v-model="form.solution" type="textarea" :rows="4" placeholder="处理步骤/解决方案，逐条编号" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="参考级别">
              <el-radio-group v-model="form.severityRef">
                <el-radio label="critical">高</el-radio>
                <el-radio label="warning">中</el-radio>
                <el-radio label="info">低</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio :label="1">启用</el-radio>
                <el-radio :label="0">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">保存</el-button>
      </div>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="故障知识详情" :visible.sync="viewVisible" width="680px" append-to-body :close-on-click-modal="false">
      <template v-if="viewForm">
        <el-descriptions :column="2" border class="detail-desc">
          <el-descriptions-item label="标题" :span="2">{{ viewForm.title }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ viewForm.category }}</el-descriptions-item>
          <el-descriptions-item label="设备类型">{{ deviceTypeLabel(viewForm.deviceType) }}</el-descriptions-item>
          <el-descriptions-item label="适用品牌">{{ viewForm.brand || '不限' }}</el-descriptions-item>
          <el-descriptions-item label="适用型号">{{ viewForm.model || '不限' }}</el-descriptions-item>
          <el-descriptions-item label="参考级别">
            <el-tag :type="severityType(viewForm.severityRef)" size="small">{{ severityLabel(viewForm.severityRef) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="浏览人次">{{ viewForm.viewCount }}</el-descriptions-item>
          <el-descriptions-item label="关键词">{{ viewForm.keywords || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="viewForm.status === 1 ? 'success' : 'info'" size="small">{{ viewForm.status === 1 ? '启用' : '停用' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建人">{{ viewForm.createBy }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">故障现象</el-divider>
        <p class="detail-text">{{ viewForm.symptom || '无' }}</p>
        <el-divider content-position="left">可能原因</el-divider>
        <p class="detail-text" style="white-space:pre-line">{{ viewForm.cause || '无' }}</p>
        <el-divider content-position="left">处理步骤</el-divider>
        <p class="detail-text" style="white-space:pre-line">{{ viewForm.solution || '无' }}</p>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import Pagination from '@/components/Pagination'
import { listFaultArticle, getFaultArticle, getFaultStats, getFaultOptions, addFaultArticle, updateFaultArticle, delFaultArticle } from '@/api/knowledge/fault'

const DEVICE_LABELS = { all: '通用', network: '网络设备', camera: '摄像头', nvr: '录像机NVR', door_controller: '门禁设备' }
const SEVERITY_LABELS = { critical: '高', warning: '中', info: '低' }

export default {
  name: 'KnowledgeFault',
  components: { Pagination },
  data() {
    return {
      loading: false,
      submitting: false,
      list: [],
      total: 0,
      stats: { total: 0, enabled: 0, disabled: 0, viewCount: 0 },
      options: { categories: [], deviceTypes: [], severities: [] },
      query: { pageNum: 1, pageSize: 10, title: '', category: '', deviceType: '', status: null },
      dialogVisible: false,
      viewVisible: false,
      viewForm: null,
      form: {},
      rules: {
        title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
        category: [{ required: true, message: '请选择分类', trigger: 'change' }],
        deviceType: [{ required: true, message: '请选择设备类型', trigger: 'change' }],
        symptom: [{ required: true, message: '请输入故障现象', trigger: 'blur' }],
        cause: [{ required: true, message: '请输入可能原因', trigger: 'blur' }],
        solution: [{ required: true, message: '请输入处理步骤', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getOptions()
    this.getStats()
    this.getList()
  },
  methods: {
    hasPerm(key) {
      const perms = this.$store.getters.permissions || []
      return perms.includes(key) || perms.includes('*:*:*')
    },
    deviceTypeLabel(t) {
      return DEVICE_LABELS[t] || t || '-'
    },
    severityLabel(s) {
      return SEVERITY_LABELS[s] || s || '-'
    },
    severityType(s) {
      if (s === 'critical') return 'danger'
      if (s === 'warning') return 'warning'
      return 'info'
    },
    getOptions() {
      getFaultOptions().then(res => {
        this.options = res || this.options
      })
    },
    getStats() {
      getFaultStats().then(res => {
        this.stats = res || this.stats
      })
    },
    getList() {
      this.loading = true
      listFaultArticle(this.query).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() {
      this.query.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.query = { pageNum: 1, pageSize: 10, title: '', category: '', deviceType: '', status: null }
      this.handleQuery()
    },
    handleAdd() {
      this.form = { id: null, title: '', category: '', deviceType: 'all', brand: '', model: '', keywords: '', symptom: '', cause: '', solution: '', severityRef: 'info', status: 1 }
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleEdit(row) {
      this.form = Object.assign({}, row)
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleView(row) {
      getFaultArticle(row.id).then(res => {
        this.viewForm = res
        this.viewVisible = true
        this.getStats()
        this.getList()
      })
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.submitting = true
        const req = this.form.id ? updateFaultArticle(this.form) : addFaultArticle(this.form)
        req.then(() => {
          this.$message.success(this.form.id ? '修改成功' : '新增成功')
          this.dialogVisible = false
          this.getList()
          this.getStats()
          this.submitting = false
        }).catch(() => { this.submitting = false })
      })
    },
    handleDelete(row) {
      this.$confirm('删除知识「' + row.title + '」后不可恢复，确定删除？', '提示', { type: 'warning' }).then(() => {
        delFaultArticle(row.id).then(() => {
          this.$message.success('删除成功')
          this.getList()
          this.getStats()
        })
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.stat-card {
  display: flex; align-items: center; background: #fff; border-radius: 8px;
  padding: 16px; box-shadow: 0 1px 4px rgba(0,0,0,.04);
}
.stat-icon {
  width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;
  font-size: 22px; margin-right: 12px;
}
.stat-num { font-size: 22px; font-weight: 600; color: #1F2937; line-height: 1.2; }
.stat-label { font-size: 13px; color: #6B7280; }
.search-card { margin-bottom: 16px; }
.detail-text { color: #374151; line-height: 1.8; margin: 4px 0 8px; }
.danger-text { color: #EF4444; }
</style>
