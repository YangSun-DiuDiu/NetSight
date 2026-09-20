<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="姓名" prop="name">
        <el-input v-model="queryParams.name" placeholder="姓名/手机号" clearable size="small" style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="在岗状态" clearable size="small" style="width: 120px">
          <el-option label="在岗" :value="1" />
          <el-option label="休假" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['workorder:repairer:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增维修人员</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="repairerList">
      <el-table-column label="姓名" prop="name" width="110" />
      <el-table-column label="手机号" prop="phone" width="130" />
      <el-table-column label="负责区域" prop="region" min-width="150" show-overflow-tooltip />
      <el-table-column label="设备类型" width="200">
        <template slot-scope="scope">
          <el-tag v-for="t in parseArr(scope.row.deviceTypes)" :key="t" size="mini" style="margin-right:4px">{{ deviceTypeText(t) }}</el-tag>
          <span v-if="!scope.row.deviceTypes">-</span>
        </template>
      </el-table-column>
      <el-table-column label="技能" prop="skills" min-width="170" show-overflow-tooltip />
      <el-table-column label="在岗状态" width="90" align="center">
        <template slot-scope="scope">
          <el-switch :value="scope.row.status === 1" @change="handleStatusChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="160" align="center" />
      <el-table-column label="操作" width="140" align="center">
        <template slot-scope="scope">
          <el-button v-hasPermi="['workorder:repairer:edit']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['workorder:repairer:remove']" size="mini" type="text" icon="el-icon-delete" style="color:#f56c6c" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="form.id ? '修改维修人员' : '新增维修人员'" :visible.sync="open" width="600px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-row>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="手机号（通知接收）" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="负责区域">
          <el-input v-model="form.region" placeholder="如：1号楼/2号楼、3号楼" />
        </el-form-item>
        <el-form-item label="设备类型">
          <el-select v-model="form.deviceTypes" multiple placeholder="可维修设备类型" style="width:100%">
            <el-option label="网络设备" value="network" />
            <el-option label="摄像头" value="camera" />
            <el-option label="NVR" value="nvr" />
            <el-option label="门禁控制器" value="door_controller" />
          </el-select>
        </el-form-item>
        <el-form-item label="技能标签">
          <el-input v-model="form.skills" placeholder="如：交换机维修,光纤熔接" />
        </el-form-item>
        <el-form-item label="在岗状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">在岗</el-radio>
            <el-radio :label="0">休假</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listRepairer, addRepairer, updateRepairer, delRepairer } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'

export default {
  name: 'Repairer',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      repairerList: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, name: undefined, status: undefined },
      open: false,
      form: {},
      rules: {
        name: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
        phone: [{ required: true, message: '手机号不能为空', trigger: 'blur' },
                { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRepairer(this.queryParams).then(response => {
        this.repairerList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleAdd() {
      this.reset()
      this.form = { status: 1, deviceTypes: [] }
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row }
      this.open = true
    },
    submitForm() {
      this.$refs['form'].validate(valid => {
        if (!valid) return
        const payload = { ...this.form }
        if (Array.isArray(payload.deviceTypes)) payload.deviceTypes = JSON.stringify(payload.deviceTypes)
        if (this.form.id) {
          updateRepairer(payload).then(() => {
            this.$modal.msgSuccess('修改成功')
            this.open = false
            this.getList()
          })
        } else {
          addRepairer(payload).then(() => {
            this.$modal.msgSuccess('维修人员已添加')
            this.open = false
            this.getList()
          })
        }
      })
    },
    handleStatusChange(row) {
      const target = row.status === 1 ? 0 : 1
      updateRepairer({ ...row, status: target, deviceTypes: JSON.stringify(parseArr(row.deviceTypes)) }).then(() => {
        this.$modal.msgSuccess(target === 1 ? '已设为在岗' : '已设为休假')
        this.getList()
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除维修人员 ' + row.name + '？', '警告', { type: 'warning' }).then(() => {
        delRepairer(row.id).then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
      })
    },
    parseArr(v) {
      if (!v) return []
      try { return JSON.parse(v) } catch (e) { return [] }
    },
    deviceTypeText(t) {
      return { network: '网络设备', camera: '摄像头', nvr: 'NVR', door_controller: '门禁控制器' }[t] || t
    },
    cancel() { this.open = false; this.reset() },
    reset() { this.form = {} }
  }
}
</script>
