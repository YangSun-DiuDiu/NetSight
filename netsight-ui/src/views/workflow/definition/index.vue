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
      <el-button v-if="isSuperAdmin" type="warning" icon="el-icon-copy-document" @click="copyVisible = true">
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
    <el-dialog title="选择性复制流程模板" :visible.sync="copyVisible" width="640px" append-to-body @open="onCopyDialogOpen">
      <el-form label-width="110px" size="small">
        <el-form-item label="目标租户">
          <el-select v-model="copyForm.targetTenantId" placeholder="选择要复制到的租户" filterable style="width:100%">
            <el-option v-for="t in tenantOptions" :key="t.id" :label="t.tenantName + '（ID:' + t.id + '）'" :value="t.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="选择模板">
          <el-table :data="sourceTemplates" size="mini" border height="260" @selection-change="onSourceSelectChange" ref="sourceTable">
            <el-table-column type="selection" width="40" />
            <el-table-column prop="flowCode" label="流程编码" width="160" align="center" />
            <el-table-column prop="flowName" label="流程名称" min-width="160" show-overflow-tooltip />
            <el-table-column prop="version" label="版本" width="70" align="center" />
            <el-table-column prop="isPublish" label="状态" width="80" align="center">
              <template slot-scope="{ row }">
                <el-tag :type="row.isPublish === 1 ? 'success' : 'info'" size="mini">
                  {{ row.isPublish === 1 ? '已发布' : '草稿' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>

        <el-form-item label="冲突策略">
          <el-radio-group v-model="copyForm.conflictStrategy">
            <el-radio label="overwrite">覆盖目标租户同编码模板（推荐）</el-radio>
            <el-radio label="skip">跳过目标租户已有模板</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copyLoading" @click="handleCopy">
          复制选中（{{ selectedFlowCodes.length }} 个）
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listDefinitions, copyTemplates, listSourceTemplates } from '@/api/workflow/workflow'
import { listTenant } from '@/api/system/tenant'
import { getToken } from '@/utils/auth'

export default {
  name: 'WorkflowDefinition',
  data() {
    return {
      loading: false,
      list: [],
      copyVisible: false,
      copyLoading: false,
      copyForm: { targetTenantId: null, flowCodes: [], conflictStrategy: 'overwrite' },
      sourceTemplates: [],
      tenantOptions: [],
      selectedFlowCodes: [],
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
    onCopyDialogOpen() {
      this.copyForm = { targetTenantId: null, flowCodes: [], conflictStrategy: 'overwrite' }
      this.selectedFlowCodes = []
      // 加载源模板列表
      listSourceTemplates().then(data => {
        this.sourceTemplates = data || []
      })
      // 加载租户下拉
      listTenant({ pageNum: 1, pageSize: 1000 }).then(data => {
        this.tenantOptions = (data && data.rows) || []
      })
    },
    onSourceSelectChange(rows) {
      this.selectedFlowCodes = rows.map(r => r.flowCode)
    },
    handleCopy() {
      if (!this.copyForm.targetTenantId) {
        this.$message.warning('请选择目标租户')
        return
      }
      if (this.selectedFlowCodes.length === 0) {
        this.$message.warning('请至少勾选一个流程模板')
        return
      }
      this.copyLoading = true
      copyTemplates({
        targetTenantId: this.copyForm.targetTenantId,
        flowCodes: this.selectedFlowCodes,
        conflictStrategy: this.copyForm.conflictStrategy
      }).then(data => {
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
