<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="渠道类型" prop="channelType">
        <el-select v-model="queryParams.channelType" placeholder="全部类型" clearable size="small" style="width: 180px">
          <el-option v-for="m in channelMeta" :key="m.type" :label="m.name" :value="m.type" />
        </el-select>
      </el-form-item>
      <el-form-item label="实例名称" prop="channelName">
        <el-input v-model="queryParams.channelName" placeholder="请输入实例名称" clearable size="small" style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作按钮 -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['alert:channel:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增渠道</el-button>
      </el-col>
    </el-row>

    <!-- 列表 -->
    <el-table v-loading="loading" :data="list">
      <el-table-column label="实例名称" prop="channelName" min-width="180" show-overflow-tooltip />
      <el-table-column label="渠道类型" width="150" align="center">
        <template slot-scope="scope">
          <el-tag size="mini">{{ channelTypeName(scope.row.channelType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.enabled === 1 ? 'success' : 'info'">{{ scope.row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="健康状态" width="100" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="healthTag(scope.row.healthStatus)">{{ healthText(scope.row.healthStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近检查" width="160" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.lastCheckTime">{{ scope.row.lastCheckTime }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="180" align="center">
        <template slot-scope="scope">
          <el-button v-hasPermi="['alert:channel:edit']" type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['alert:channel:remove']" type="text" size="mini" icon="el-icon-delete" class="el-button--text-danger" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="form.id ? '修改渠道' : '新增渠道'" :visible.sync="open" width="620px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="渠道类型" prop="channelType">
          <el-select v-model="form.channelType" placeholder="请选择渠道类型" :disabled="!!form.id" @change="onTypeChange">
            <el-option v-for="m in channelMeta" :key="m.type" :label="m.name + '（' + m.type + '）'" :value="m.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="实例名称" prop="channelName">
          <el-input v-model="form.channelName" placeholder="如：生产环境-PushPlus" />
        </el-form-item>
        <!-- 动态参数表单 -->
        <template v-for="(f, idx) in currentFields">
          <el-form-item :key="idx" :label="f.label" :prop="'config_' + f.key">
            <el-input v-if="f.type === 'text' || f.type === 'number'" v-model="formConfig[f.key]" :type="f.type === 'number' ? 'number' : 'text'" :placeholder="'请输入' + f.label" />
            <el-input v-else-if="f.type === 'password'" v-model="formConfig[f.key]" type="password" show-password :placeholder="'请输入' + f.label" />
            <el-input v-else-if="f.type === 'textarea'" v-model="formConfig[f.key]" type="textarea" :rows="3" :placeholder="'请输入' + f.label" />
            <el-switch v-else-if="f.type === 'switch'" v-model="formConfig[f.key]" />
            <el-select v-else-if="f.type && f.type.indexOf('select:') === 0" v-model="formConfig[f.key]" :placeholder="'请选择' + f.label">
              <el-option v-for="opt in f.type.substring(7).split(',')" :key="opt" :label="opt" :value="opt" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注说明" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="open = false">取 消</el-button>
        <el-button type="primary" @click="submitForm">确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { listChannel, getChannel, addChannel, updateChannel, delChannel, getChannelMeta } from '@/api/alert/channel'

export default {
  name: 'AlertChannel',
  data() {
    return {
      loading: true,
      showSearch: true,
      list: [],
      total: 0,
      channelMeta: [],
      queryParams: { pageNum: 1, pageSize: 10, channelType: undefined, channelName: undefined },
      open: false,
      form: { id: undefined, channelType: undefined, channelName: undefined, enabled: 1, remark: undefined },
      formConfig: {},
      rules: {
        channelType: [{ required: true, message: '请选择渠道类型', trigger: 'change' }],
        channelName: [{ required: true, message: '请输入实例名称', trigger: 'blur' }]
      }
    }
  },
  computed: {
    currentFields() {
      const m = this.channelMeta.find(x => x.type === this.form.channelType)
      return m ? m.fields : []
    }
  },
  created() {
    this.loadMeta()
    this.getList()
  },
  methods: {
    loadMeta() {
      getChannelMeta().then(data => { this.channelMeta = data || [] })
    },
    getList() {
      this.loading = true
      listChannel(this.queryParams).then(data => {
        this.list = data.rows || []
        this.total = data.total || 0
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    channelTypeName(t) {
      const m = this.channelMeta.find(x => x.type === t)
      return m ? m.name : t
    },
    healthTag(s) {
      return s === 'healthy' ? 'success' : (s === 'down' ? 'danger' : 'info')
    },
    healthText(s) {
      return s === 'healthy' ? '正常' : (s === 'down' ? '异常' : '未知')
    },
    onTypeChange() {
      this.formConfig = {}
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.queryParams = { pageNum: 1, pageSize: 10, channelType: undefined, channelName: undefined }; this.getList() },
    handleAdd() {
      this.form = { id: undefined, channelType: undefined, channelName: undefined, enabled: 1, remark: undefined }
      this.formConfig = {}
      this.open = true
      this.$nextTick(() => { this.$refs.form && this.$refs.form.clearValidate() })
    },
    handleUpdate(row) {
      getChannel(row.id).then(data => {
        this.form = { id: data.id, channelType: data.channelType, channelName: data.channelName, enabled: data.enabled, remark: data.remark }
        try { this.formConfig = data.configJson ? JSON.parse(data.configJson) : {} } catch (e) { this.formConfig = {} }
        this.open = true
      })
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        const payload = {
          id: this.form.id,
          channelType: this.form.channelType,
          channelName: this.form.channelName,
          enabled: this.form.enabled,
          remark: this.form.remark,
          configJson: JSON.stringify(this.formConfig || {})
        }
        const req = payload.id ? updateChannel(payload) : addChannel(payload)
        req.then(() => {
          this.$message.success(payload.id ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        })
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除渠道「' + row.channelName + '」？', '提示', { type: 'warning' }).then(() => {
        return delChannel(row.id)
      }).then(() => {
        this.$message.success('删除成功')
        this.getList()
      }).catch(() => {})
    }
  }
}
</script>
