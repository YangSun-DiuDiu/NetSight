<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="关键字" prop="keyword">
        <el-input v-model="queryParams.keyword" placeholder="模块/操作内容/URI" clearable size="small" style="width: 220px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作日志列表 -->
    <el-table border v-loading="loading" :data="logList">
      <el-table-column label="ID" prop="id" width="70" align="center" />
      <el-table-column label="模块" prop="deviceName" width="120" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" type="info">{{ scope.row.deviceName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作内容" prop="content" min-width="180" show-overflow-tooltip align="center">
        <template slot-scope="scope">
          {{ parseContent(scope.row.content) }}
        </template>
      </el-table-column>
      <el-table-column label="来源 IP" prop="deviceIp" width="130" align="center" />
      <el-table-column label="请求 URI" prop="location" min-width="180" show-overflow-tooltip  align="center"/>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusTag(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作时间" prop="createTime" width="160" align="center" />
      <el-table-column label="操作" align="center" width="110" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button type="text" size="mini" icon="el-icon-view" @click="handleDetail(scope.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-show="total > 0"
      :total="total"
      :page-num.sync="queryParams.pageNum"
      :page-size.sync="queryParams.pageSize"
      layout="total, prev, pager, next, jumper"
      @current-change="getList"
    />

    <!-- 详情弹窗 -->
    <el-dialog title="操作日志详情" :visible.sync="detailVisible" width="600px" append-to-body>
      <el-descriptions :column="1" border v-if="currentRow">
        <el-descriptions-item label="ID">{{ currentRow.id }}</el-descriptions-item>
        <el-descriptions-item label="模块">{{ currentRow.deviceName }}</el-descriptions-item>
        <el-descriptions-item label="操作内容">{{ parseContent(currentRow.content) }}</el-descriptions-item>
        <el-descriptions-item label="来源 IP">{{ currentRow.deviceIp }}</el-descriptions-item>
        <el-descriptions-item label="请求 URI">{{ currentRow.location }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(currentRow.status) }}</el-descriptions-item>
        <el-descriptions-item label="操作时间">{{ currentRow.createTime }}</el-descriptions-item>
        <el-descriptions-item label="扩展信息" v-if="currentRow.labelsJson">
          <pre class="json-preview">{{ formatJson(currentRow.labelsJson) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script>
import { listOperationLog } from '@/api/alert/operationLog'

export default {
  name: 'OperationLog',
  data() {
    return {
      loading: true,
      logList: [],
      total: 0,
      showSearch: true,
      detailVisible: false,
      currentRow: null,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        keyword: ''
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listOperationLog(this.queryParams).then(res => {
        this.logList = res.data.rows || res.data.records || []
        this.total = res.data.total || 0
        this.loading = false
      }).catch(() => {
        this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    parseContent(content) {
      if (!content) return '-'
      // content 存储的是操作内容（来自 OperLogAspect 的 labels），尝试解析
      try {
        const obj = JSON.parse(content)
        if (obj.action) return obj.action
      } catch (e) { /* 非 JSON，直接返回 */ }
      return content
    },
    formatJson(str) {
      try {
        return JSON.stringify(JSON.parse(str), null, 2)
      } catch (e) {
        return str
      }
    },
    statusTag(status) {
      const map = {
        sent: 'success',
        failed: 'danger',
        pending: 'warning'
      }
      return map[status] || 'info'
    },
    statusText(status) {
      const map = {
        sent: '成功',
        failed: '失败',
        pending: '待处理'
      }
      return map[status] || status
    },
    handleDetail(row) {
      this.currentRow = row
      this.detailVisible = true
    }
  }
}
</script>

<style scoped>
.json-preview {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
}
</style>
