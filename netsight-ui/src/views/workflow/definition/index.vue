<template>
  <div class="app-container">
    <!-- 页头统计 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-document-copy" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ defCount }}</div>
            <div class="stat-label">流程总数</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-circle-check" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ publishedCount }}</div>
            <div class="stat-label">已发布</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-video-play" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ activeCount }}</div>
            <div class="stat-label">已激活</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F1F0FE;color:#8B5CF6">
            <i class="el-icon-setting" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ draftCount }}</div>
            <div class="stat-label">未发布草稿</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 工具栏 -->
    <el-card class="box-card filter-card">
      <el-button type="primary" icon="el-icon-plus" @click="createFlow">新建流程</el-button>
      <el-button v-if="isSuperAdmin" type="warning" icon="el-icon-copy-document" @click="openCopyDialog">
        复制模板到租户
      </el-button>
      <el-button icon="el-icon-refresh" style="float:right" @click="getList">刷新</el-button>
    </el-card>

    <!-- 定义列表 -->
    <el-card class="box-card">
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column prop="flowCode" label="流程编码" width="160" align="center" show-overflow-tooltip />
        <el-table-column prop="flowName" label="流程名称" min-width="180" align="center" show-overflow-tooltip />
        <el-table-column prop="category" label="流程类别" width="120" align="center">
          <template slot-scope="{ row }">{{ row.category || '—' }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80" align="center" />
        <el-table-column prop="isPublish" label="发布状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.isPublish === 1 ? 'success' : 'info'" size="mini">
              {{ row.isPublish === 1 ? '已发布' : (row.isPublish === 9 ? '已失效' : '未发布') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="activityStatus" label="激活状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.activityStatus === 1 ? 'primary' : 'warning'" size="mini">
              {{ row.activityStatus === 1 ? '激活' : '挂起' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
        <el-table-column label="操作" align="center" width="150" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" icon="el-icon-edit-outline" @click="editFlow(row)">设计</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 复制模板弹窗（超管） -->
    <el-dialog title="复制流程模板到租户" :visible.sync="copyVisible" width="460px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="源租户ID">
          <el-input v-model="copyForm.sourceTenantId" placeholder="预置模板所在租户，默认 1" />
        </el-form-item>
        <el-form-item label="目标租户ID">
          <el-input v-model="copyForm.targetTenantId" placeholder="要复制到的租户" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copyLoading" @click="handleCopy">确认复制</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listDefinitions, copyTemplates } from '@/api/workflow/workflow'
import { getToken } from '@/utils/auth'

export default {
  name: 'WorkflowDefinition',
  data() {
    return {
      loading: false,
      list: [],
      copyVisible: false,
      copyLoading: false,
      copyForm: { sourceTenantId: 1, targetTenantId: null },
      isSuperAdmin: false
    }
  },
  computed: {
    defCount() { return this.list.length },
    publishedCount() { return this.list.filter(r => r.isPublish === 1).length },
    activeCount() { return this.list.filter(r => r.activityStatus === 1).length },
    draftCount() { return this.list.filter(r => r.isPublish === 0).length }
  },
  created() {
    this.isSuperAdmin = this.$store.getters.roles.includes('super_admin')
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listDefinitions().then(data => {
        this.list = data || []
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    createFlow() {
      this.$router.push('/workflow/designer')
    },
    editFlow(row) {
      this.$router.push('/workflow/designer?flowId=' + row.id)
    },
    openCopyDialog() {
      this.copyForm = { sourceTenantId: 1, targetTenantId: null }
      this.copyVisible = true
    },
    handleCopy() {
      if (!this.copyForm.targetTenantId) {
        this.$message.warning('请填写目标租户ID')
        return
      }
      this.copyLoading = true
      copyTemplates(this.copyForm).then(data => {
        this.$message.success('已复制 ' + data + ' 套流程模板到目标租户')
        this.copyVisible = false
        this.copyLoading = false
      }).catch(() => { this.copyLoading = false })
    }
  }
}
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.filter-card { margin-bottom: 16px; }
.box-card { border-radius: 12px; }
</style>
