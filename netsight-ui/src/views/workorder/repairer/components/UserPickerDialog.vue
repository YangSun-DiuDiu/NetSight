<template>
  <el-dialog title="选择用户" :visible="visible" width="720px" append-to-body
             :close-on-click-modal="false" @close="close">
    <el-form :inline="true" class="picker-search">
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="姓名/手机号/登录账号" clearable
                  size="small" style="width: 220px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
      </el-form-item>
    </el-form>

    <el-table ref="userTable" border v-loading="loading" :data="userList" highlight-current-row
              @row-click="handleRowClick" style="width: 100%">
      <el-table-column label="姓名" prop="realName" width="180" align="center" />
      <el-table-column label="手机号" prop="phone" width="180" align="center" />
      <el-table-column label="登录账号" prop="username" min-width="180" align="center" />
    </el-table>
    <div v-if="!loading && userList.length === 0" class="empty-tip">暂无可绑定的用户</div>

    <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum"
                :limit.sync="query.pageSize" @pagination="getList" />

    <div slot="footer" class="dialog-footer">
      <el-button type="primary" :disabled="!selected" @click="confirm">确 定</el-button>
      <el-button @click="close">取 消</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { listRepairerUserOptions } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'

export default {
  name: 'UserPickerDialog',
  components: { Pagination },
  props: {
    visible: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      userList: [],
      total: 0,
      selected: null,
      query: { pageNum: 1, pageSize: 10, keyword: undefined }
    }
  },
  watch: {
    visible(value) {
      if (value) {
        this.selected = null
        this.query.pageNum = 1
        this.query.keyword = undefined
        this.getList()
      }
    }
  },
  methods: {
    getList() {
      this.loading = true
      listRepairerUserOptions(this.query).then(response => {
        this.userList = response.rows || []
        this.total = response.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() {
      this.query.pageNum = 1
      this.getList()
    },
    handleRowClick(row) {
      this.selected = row
      this.$refs.userTable.setCurrentRow(row)
    },
    confirm() {
      if (!this.selected) return
      this.$emit('select', this.selected)
      this.close()
    },
    close() {
      this.$emit('update:visible', false)
    }
  }
}
</script>

<style scoped>
.picker-search {
  margin-bottom: 8px;
}
.empty-tip {
  padding: 18px 0;
  color: #909399;
  text-align: center;
}
</style>
