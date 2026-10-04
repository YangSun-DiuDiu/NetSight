<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="关键字" prop="keyword">
        <el-input v-model="queryParams.keyword" placeholder="备件编号/名称/工单号" clearable size="small" style="width: 200px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="操作类型" prop="recordType">
        <el-select v-model="queryParams.recordType" placeholder="操作类型" clearable size="small" style="width: 130px">
          <el-option label="入库" value="in" />
          <el-option label="领用出库" value="out" />
          <el-option label="返修" value="repair" />
          <el-option label="返修入库" value="return_in" />
          <el-option label="报废" value="scrap" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table border v-loading="loading" :data="recordList">
      <el-table-column label="操作时间" prop="createTime" width="225" align="center" />
      <el-table-column label="备件编号" prop="partNo" width="215" show-overflow-tooltip  align="center"/>
      <el-table-column label="备件名称" prop="partName" width="70" show-overflow-tooltip  align="center"/>
      <el-table-column label="操作类型" width="110" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="recordTypeType(scope.row.recordType)">{{ recordTypeText(scope.row.recordType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="100" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.recordType === 'repair'" style="color:#e6a23c;font-weight:600">送修 {{ scope.row.quantity }} {{ scope.row.unit }}</span>
          <span v-else :style="{ color: scope.row.quantity > 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">
            {{ scope.row.quantity > 0 ? '+' : '' }}{{ scope.row.quantity }} {{ scope.row.unit }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="关联工单" min-width="150" align="center">
        <template slot-scope="scope">{{ scope.row.orderNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作人" prop="operator" width="100" align="center" />
      <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip  align="center"/>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
  </div>
</template>

<script>
import { listRecord } from '@/api/spare/record'
import Pagination from '@/components/Pagination'

export default {
  name: 'SpareRecord',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      recordList: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, keyword: undefined, recordType: undefined }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRecord(this.queryParams).then(response => {
        this.recordList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    recordTypeText(t) {
      return { in: '入库', out: '领用出库', repair: '返修', return_in: '返修入库', scrap: '报废' }[t] || t
    },
    recordTypeType(t) {
      return { in: 'success', out: 'danger', repair: 'warning', return_in: 'primary', scrap: 'info' }[t] || 'info'
    }
  }
}
</script>
