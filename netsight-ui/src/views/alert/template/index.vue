<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="模板名称" prop="templateName">
        <el-input v-model="queryParams.templateName" placeholder="请输入模板名称" clearable size="small" style="width: 180px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="通道" prop="channelType">
        <el-select v-model="queryParams.channelType" placeholder="适用通道" clearable size="small" style="width: 140px">
          <el-option v-for="t in channelTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作按钮区 -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['alert:template:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增模板</el-button>
      </el-col>
    </el-row>

    <!-- 模板列表 -->
    <el-table border v-loading="loading" :data="templateList">
      <el-table-column label="模板编码" prop="templateCode" width="200"  align="center"/>
      <el-table-column label="模板名称" prop="templateName" width="95"  align="center"/>
      <el-table-column label="绑定通道" width="115" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="channelTagType(scope.row.channelType)">{{ channelLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模板内容" prop="content" min-width="340" show-overflow-tooltip  align="center"/>
      <el-table-column label="启用" width="80" align="center">
        <template slot-scope="scope">
          <el-switch v-model="scope.row.enabled" :active-value="1" :inactive-value="0" @change="handleEnabledChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="140" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasPermi="['alert:template:edit']" type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['alert:template:remove']" type="text" size="mini" icon="el-icon-delete" class="el-button--text-danger" @click="handleDelete(scope.row)" style="color:#f56c6c">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="templateForm.id ? '修改消息模板' : '新增消息模板'" :visible.sync="open" width="620px" append-to-body :close-on-click-modal="false">
      <el-form ref="templateForm" :model="templateForm" :rules="templateRules" label-width="100px">
        <el-row :gutter="10">
          <el-col :span="12">
            <el-form-item label="模板编码" prop="templateCode">
              <el-input v-model="templateForm.templateCode" placeholder="如 tpl_device_offline" :disabled="!!templateForm.id" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模板名称" prop="templateName">
              <el-input v-model="templateForm.templateName" placeholder="如：设备离线告警" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="绑定通道" prop="channelId">
          <el-select v-model="templateForm.channelId" placeholder="选择已配置好参数的通道实例" style="width: 100%" @change="onChannelChange">
            <el-option v-for="ch in enabledChannelInstances" :key="ch.id"
              :label="channelTypeName(ch.channelType) + ' · ' + ch.channelName" :value="ch.id" />
          </el-select>
          <div style="font-size:12px;color:#909399;line-height:1.4;margin-top:4px;">
            仅列出本租户已启用并配好参数的通道实例；不选则按通道类型使用默认模板
          </div>
        </el-form-item>
        <el-form-item label="模板内容" prop="content">
          <el-input v-model="templateForm.content" type="textarea" :rows="5"
            :placeholder="'支持占位符：{{device_name}} {{device_ip}} {{device_type}} {{location}} {{severity}} {{time}} {{content}}'" />
        </el-form-item>
        <el-form-item label="是否启用" prop="enabled">
          <el-radio-group v-model="templateForm.enabled">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确 定</el-button>
        <el-button @click="open = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTemplate, addTemplate, updateTemplate, delTemplate } from '@/api/alert/template'
import { channelOptions } from '@/api/alert/channel'
import Pagination from '@/components/Pagination'

export default {
  name: 'AlertTemplate',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      templateList: [],
      total: 0,
      channelInstanceList: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        templateName: undefined,
        channelType: undefined
      },
      channelTypeOptions: [
        { value: 'aliyun_sms', label: '阿里云短信' },
        { value: 'tencent_sms', label: '腾讯云短信' },
        { value: 'sms', label: '短信（通用）' },
        { value: 'wechat', label: '微信公众号' },
        { value: 'dingtalk', label: '钉钉群机器人' },
        { value: 'wechat_work', label: '企业微信群机器人' },
        { value: 'wechat_app', label: '企业微信应用消息' },
        { value: 'feishu', label: '飞书群机器人' },
        { value: 'serverchan', label: 'Server酱' },
        { value: 'pushplus', label: 'PushPlus 推送' },
        { value: 'email', label: '邮件' },
        { value: 'webhook', label: '通用 Webhook' }
      ],
      open: false,
      submitLoading: false,
      templateForm: {
        id: undefined,
        templateCode: '',
        templateName: '',
        channelType: 'wechat',
        channelId: undefined,
        content: '',
        enabled: 1
      },
      templateRules: {
        templateCode: [{ required: true, message: '模板编码不能为空', trigger: 'blur' }],
        templateName: [{ required: true, message: '模板名称不能为空', trigger: 'blur' }],
        channelId: [{ required: true, message: '请选择绑定通道', trigger: 'change' }],
        content: [{ required: true, message: '模板内容不能为空', trigger: 'blur' }]
      }
    }
  },
  computed: {
    enabledChannelInstances() {
      return this.channelInstanceList.filter(c => c.enabled === 1 || c.enabled === '1')
    }
  },
  created() {
    this.getList()
    this.loadChannels()
  },
  methods: {
    loadChannels() {
      channelOptions().then(rows => {
        this.channelInstanceList = rows || []
      }).catch(() => { this.channelInstanceList = [] })
    },
    getList() {
      this.loading = true
      listTemplate(this.queryParams).then(response => {
        this.templateList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    onChannelChange(id) {
      // 选中实例后自动带出 channelType 作为类型兜底
      const ch = this.channelInstanceList.find(c => c.id === id)
      if (ch) {
        this.templateForm.channelType = ch.channelType
      }
    },
    handleAdd() {
      this.templateForm = { id: undefined, templateCode: '', templateName: '', channelType: 'wechat', channelId: undefined, content: '', enabled: 1 }
      this.open = true
      this.$nextTick(() => this.$refs.templateForm && this.$refs.templateForm.clearValidate())
    },
    handleUpdate(row) {
      this.templateForm = {
        id: row.id,
        templateCode: row.templateCode,
        templateName: row.templateName,
        channelType: row.channelType,
        channelId: row.channelId,
        content: row.content,
        enabled: row.enabled
      }
      this.open = true
      this.$nextTick(() => this.$refs.templateForm && this.$refs.templateForm.clearValidate())
    },
    submitForm() {
      this.$refs.templateForm.validate(valid => {
        if (!valid) return
        this.submitLoading = true
        const request = this.templateForm.id ? updateTemplate(this.templateForm) : addTemplate(this.templateForm)
        request.then(() => {
          this.$modal.msgSuccess(this.templateForm.id ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        }).finally(() => { this.submitLoading = false })
      })
    },
    handleEnabledChange(row) {
      updateTemplate(row).then(() => {
        this.$modal.msgSuccess(row.enabled === 1 ? '已启用' : '已停用')
      }).catch(() => {
        row.enabled = row.enabled === 1 ? 0 : 1
      })
    },
    handleDelete(row) {
      this.$modal.confirm('确认删除模板「' + row.templateName + '」吗？').then(() => {
        return delTemplate(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('删除成功')
      }).catch(() => {})
    },
    channelLabel(row) {
      // 优先显示绑定的具体通道实例名，否则显示类型名
      if (row.channelId) {
        const ch = this.channelInstanceList.find(c => c.id === row.channelId)
        if (ch) return this.channelTypeName(ch.channelType) + ' · ' + ch.channelName
      }
      return this.channelTypeName(row.channelType)
    },
    channelTypeName(t) {
      const o = this.channelTypeOptions.find(x => x.value === t)
      return o ? o.label : t
    },
    channelTagType(t) {
      if (t === 'pushplus' || t === 'serverchan') return 'warning'
      if (t === 'aliyun_sms' || t === 'tencent_sms' || t === 'sms') return 'danger'
      return 'success'
    }
  }
}
</script>
