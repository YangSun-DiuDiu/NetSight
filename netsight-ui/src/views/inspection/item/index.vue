<template>
  <div class="app-container">
    <!-- 搜索 -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query">
        <el-form-item label="点检项名称">
          <el-input v-model="query.itemName" placeholder="点检项名称" clearable style="width: 180px" @keyup.enter.native="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 点检项表格 -->
    <el-card shadow="never">
      <div slot="header" class="card-head">
        <span>点检项库</span>
        <el-button v-if="hasPerm('inspection:item:add')" type="primary" icon="el-icon-plus" @click="openAdd">新增点检项</el-button>
      </div>
      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="排序" prop="sort" width="70" align="center" />
        <el-table-column label="点检项名称" prop="itemName" min-width="130" />
        <el-table-column label="检查内容" prop="checkContent" min-width="180" show-overflow-tooltip />
        <el-table-column label="检查标准" prop="checkStandard" min-width="180" show-overflow-tooltip />
        <el-table-column label="结果类型" width="100" align="center">
          <template slot-scope="scope">
            <el-tag :type="scope.row.resultType === 'text' ? 'warning' : 'success'" size="mini">{{ scope.row.resultType === 'text' ? '文本填写' : '勾选' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'info'" size="mini">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template slot-scope="scope">
            <el-button v-if="hasPerm('inspection:item:edit')" type="primary" size="mini" icon="el-icon-edit" @click="openEdit(scope.row)">修改</el-button>
            <el-button v-if="hasPerm('inspection:item:remove')" type="danger" size="mini" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="getList" />
    </el-card>

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="form.id ? '修改点检项' : '新增点检项'" :visible.sync="dialogVisible" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="点检项名称" prop="itemName">
          <el-input v-model="form.itemName" placeholder="如：设备运行指示灯" />
        </el-form-item>
        <el-form-item label="检查内容">
          <el-input v-model="form.checkContent" type="textarea" :rows="2" placeholder="要检查什么" />
        </el-form-item>
        <el-form-item label="检查标准">
          <el-input v-model="form.checkStandard" type="textarea" :rows="2" placeholder="达标要求" />
        </el-form-item>
        <el-form-item label="结果类型">
          <el-radio-group v-model="form.resultType">
            <el-radio-button label="check">勾选（正常/异常）</el-radio-button>
            <el-radio-button label="text">文本填写</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
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
import { listInspectionItem, addInspectionItem, updateInspectionItem, delInspectionItem } from '@/api/inspection/inspection'
import Pagination from '@/components/Pagination'

export default {
  name: 'InspectionItem',
  components: { Pagination },
  data() {
    return {
      loading: false,
      saving: false,
      list: [],
      total: 0,
      query: { pageNum: 1, pageSize: 10, itemName: '' },
      dialogVisible: false,
      form: {},
      rules: {
        itemName: [{ required: true, message: '点检项名称不能为空', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listInspectionItem(this.query).then(res => {
        this.list = res.rows || []
        this.total = res.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() { this.query.pageNum = 1; this.getList() },
    resetQuery() {
      this.query = { pageNum: 1, pageSize: 10, itemName: '' }
      this.handleQuery()
    },
    openAdd() {
      this.form = { resultType: 'check', status: 1, sort: 0 }
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    openEdit(row) {
      this.form = { ...row }
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleSave() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.saving = true
        const req = this.form.id ? updateInspectionItem(this.form) : addInspectionItem(this.form)
        req.then(() => {
          this.saving = false
          this.dialogVisible = false
          this.$message.success('保存成功')
          this.getList()
        }).catch(() => { this.saving = false })
      })
    },
    handleDelete(row) {
      this.$confirm('确定删除点检项「' + row.itemName + '」？', '提示', { type: 'warning' }).then(() => {
        delInspectionItem(row.id).then(() => {
          this.$message.success('删除成功')
          this.getList()
        })
      }).catch(() => {})
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
</style>
