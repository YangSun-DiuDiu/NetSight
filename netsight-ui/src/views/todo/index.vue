<template>
  <div class="app-container todo-page">
    <!-- 统计卡 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <div class="stat-card stat-pending">
          <div class="stat-value">{{ stats.workOrderPending }}</div>
          <div class="stat-label">待处理工单</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card stat-mine">
          <div class="stat-value">{{ stats.workOrderMine }}</div>
          <div class="stat-label">我的维修工单</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card stat-insp">
          <div class="stat-value">{{ stats.inspectionTodo }}</div>
          <div class="stat-label">待执行点检</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card stat-total">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">总待办</div>
        </div>
      </el-col>
    </el-row>

    <!-- 类型筛选 -->
    <el-card class="box-card filter-card">
      <el-radio-group v-model="query.type" @change="handleSearch" class="type-group">
        <el-radio-button label="all">全部待办</el-radio-button>
        <el-radio-button label="work_order">工单待办</el-radio-button>
        <el-radio-button label="inspection">点检待办</el-radio-button>
      </el-radio-group>
      <el-button type="primary" icon="el-icon-refresh" style="float:right" @click="handleRefresh">刷新</el-button>
    </el-card>

    <!-- 待办列表 -->
    <el-card class="box-card">
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="待办类型" width="110" align="center">
          <template slot-scope="{ row }">
            <el-tag v-if="row.bizType === 'work_order'" type="warning" effect="light">工单</el-tag>
            <el-tag v-else type="success" effect="light">点检</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bizNo" label="业务编号" width="200" show-overflow-tooltip />
        <el-table-column prop="title" label="待办事项" min-width="220" show-overflow-tooltip />
        <el-table-column prop="deviceName" label="关联设备" width="150" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.deviceName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
        <el-table-column prop="statusText" label="状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.statusText === '待派单' ? 'danger' : (row.statusText === '待执行' ? 'primary' : 'warning')"
                    size="mini" effect="plain">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
        <el-table-column label="操作" width="110" align="center">
          <template slot-scope="{ row }">
            <el-button v-if="row.bizType === 'work_order'" type="primary" size="mini"
                       icon="el-icon-s-order" @click="goWorkOrder">去处理</el-button>
            <el-button v-else type="success" size="mini"
                       icon="el-icon-s-check" @click="goInspection">去处理</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize"
                  @pagination="getList" />
    </el-card>
  </div>
</template>

<script>
import { getTodoStats, listTodo } from '@/api/todo/todo'
import Pagination from '@/components/Pagination'

export default {
  name: 'Todo',
  components: { Pagination },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      stats: { workOrderPending: 0, workOrderMine: 0, inspectionTodo: 0, total: 0 },
      query: { type: 'all', pageNum: 1, pageSize: 10 }
    }
  },
  created() {
    this.getStats()
    this.getList()
  },
  methods: {
    getStats() {
      getTodoStats().then(data => {
        this.stats = data || this.stats
      })
    },
    getList() {
      this.loading = true
      listTodo(this.query).then(data => {
        this.list = data.rows || []
        this.total = data.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
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
    }
  }
}
</script>

<style scoped>
.todo-page .stat-row { margin-bottom: 16px; }
.stat-card {
  background: #fff; border-radius: 12px; padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
  border-left: 4px solid #205CF5;
}
.stat-card .stat-value { font-size: 30px; font-weight: 700; color: #1F2937; line-height: 1.2; }
.stat-card .stat-label { margin-top: 6px; font-size: 13px; color: #6B7280; }
.stat-pending { border-left-color: #EF4444; }
.stat-mine { border-left-color: #F59E0B; }
.stat-insp { border-left-color: #10B981; }
.stat-total { border-left-color: #205CF5; }
.filter-card { margin-bottom: 16px; }
.type-group .el-radio-button__inner { border-radius: 6px !important; }
.box-card { border-radius: 12px; }
</style>
