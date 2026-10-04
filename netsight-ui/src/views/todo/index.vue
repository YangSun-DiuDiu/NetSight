<template>
  <div class="app-container todo-page">
    <!-- 统计卡 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-document" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.workOrderPending }}</div>
            <div class="stat-label">待处理工单</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-user" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.workOrderMine }}</div>
            <div class="stat-label">我的维修工单</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-date" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.inspectionTodo }}</div>
            <div class="stat-label">待执行点检</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F1F0FE;color:#8B5CF6">
            <i class="el-icon-s-order" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.total }}</div>
            <div class="stat-label">总待办</div>
          </div>
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
        <el-table-column prop="bizNo" label="业务编号" width="160" show-overflow-tooltip  align="center"/>
        <el-table-column prop="title" label="待办事项" min-width="200" show-overflow-tooltip  align="center"/>
        <el-table-column prop="deviceName" label="关联设备" min-width="150" show-overflow-tooltip align="center">
          <template slot-scope="{ row }">{{ row.deviceName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="120" show-overflow-tooltip  align="center"/>
        <el-table-column prop="statusText" label="状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="row.statusText === '待派单' ? 'danger' : (row.statusText === '待执行' ? 'primary' : 'warning')"
                    size="mini">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
        <el-table-column label="操作" align="center" width="140" class-name="small-padding fixed-width">
          <template slot-scope="{ row }">
            <el-button v-if="row.bizType === 'work_order'" type="text" size="mini"
                       icon="el-icon-s-order" @click="goWorkOrder">去处理</el-button>
            <el-button v-else type="text" size="mini"
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
.filter-card { margin-bottom: 16px; }
.type-group .el-radio-button__inner { border-radius: 6px !important; }
.box-card { border-radius: 12px; }
</style>
