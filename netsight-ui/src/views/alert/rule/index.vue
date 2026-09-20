<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="规则名称" prop="ruleName">
        <el-input v-model="queryParams.ruleName" placeholder="请输入规则名称" clearable size="small" style="width: 180px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="事件类型" prop="eventType">
        <el-select v-model="queryParams.eventType" placeholder="事件类型" clearable size="small" style="width: 160px">
          <el-option v-for="dict in eventTypeOptions" :key="dict.value" :label="dict.label" :value="dict.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="enabled">
        <el-select v-model="queryParams.enabled" placeholder="启用状态" clearable size="small" style="width: 110px">
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
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
        <el-button v-hasPermi="['alert:rule:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增规则</el-button>
      </el-col>
    </el-row>

    <!-- 规则列表 -->
    <el-table v-loading="loading" :data="ruleList">
      <el-table-column label="规则名称" prop="ruleName" min-width="150" />
      <el-table-column label="事件类型" width="120" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="eventTypeTag(scope.row.eventType)">{{ eventTypeText(scope.row.eventType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="触发条件" width="130" align="center">
        <template slot-scope="scope">{{ conditionText(scope.row.conditionJson) }}</template>
      </el-table-column>
      <el-table-column label="接收人" min-width="150" show-overflow-tooltip>
        <template slot-scope="scope">{{ contactIdsText(scope.row.receiverStrategyJson, scope.row.channelsJson) }}</template>
      </el-table-column>
      <el-table-column label="通知通道" width="180" align="center">
        <template slot-scope="scope">
          <el-tag v-for="cid in channels(scope.row.channelsJson)" :key="cid" size="mini" class="mr-4" :type="channelTagType(cid)">{{ channelName(cid) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template slot-scope="scope">
          <el-switch v-model="scope.row.enabled" :active-value="1" :inactive-value="0" @change="handleEnabledChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template slot-scope="scope">
          <el-button v-hasPermi="['alert:rule:edit']" type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['alert:rule:remove']" type="text" size="mini" icon="el-icon-delete" class="el-button--text-danger" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="ruleForm.id ? '修改通知规则' : '新增通知规则'" :visible.sync="open" width="620px" append-to-body :close-on-click-modal="false">
      <el-form ref="ruleForm" :model="ruleForm" :rules="ruleRules" label-width="100px">
        <el-row :gutter="10">
          <el-col :span="12">
            <el-form-item label="规则名称" prop="ruleName">
              <el-input v-model="ruleForm.ruleName" placeholder="如：设备离线高危告警" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="事件类型" prop="eventType">
              <el-select v-model="ruleForm.eventType" placeholder="选择事件类型" style="width: 100%">
                <el-option v-for="dict in eventTypeOptions" :key="dict.value" :label="dict.label" :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="10">
          <el-col :span="12">
            <el-form-item label="触发条件" prop="conditionJson">
              <el-select v-model="conditionSeverity" placeholder="级别条件（可不选=全部）" clearable style="width: 100%">
                <el-option label="仅高危 critical" value="critical" />
                <el-option label="仅一般 warning" value="warning" />
                <el-option label="仅恢复 resolved" value="resolved" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="消息模板" prop="templateId">
              <el-select v-model="ruleForm.templateId" placeholder="选择模板" style="width: 100%">
                <el-option v-for="tpl in templateOptions" :key="tpl.id" :label="tpl.templateName + '（' + tpl.channelType + '）'" :value="tpl.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="通知通道" prop="channels">
          <el-select v-model="ruleForm.channels" multiple filterable placeholder="选择通知渠道实例（可多选）" style="width: 100%">
            <el-option v-for="ch in channelOptions" :key="ch.id" :label="ch.channelName + '（' + channelTypeName(ch.channelType) + '）'" :value="ch.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接收人" prop="contactIds">
          <el-select v-model="ruleForm.contactIds" multiple filterable placeholder="选择通知联系人（短信/公众号按此发送）" style="width: 100%">
            <el-option v-for="c in contactOptions" :key="c.id" :label="c.name + '（' + (c.mobile || '未填手机号') + '）'" :value="c.id" />
          </el-select>
          <div class="tip-text">仅勾选「推送(PushPlus)」时可不选接收人（按租户 Token 自动推送所有关注者）。</div>
        </el-form-item>
        <el-form-item label="是否启用" prop="enabled">
          <el-radio-group v-model="ruleForm.enabled">
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
import { listRule, addRule, updateRule, delRule } from '@/api/alert/rule'
import { listTemplateOptions } from '@/api/alert/template'
import { contactOptions } from '@/api/alert/contact'
import { channelOptions } from '@/api/alert/channel'
import Pagination from '@/components/Pagination'

export default {
  name: 'AlertRule',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      ruleList: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        ruleName: undefined,
        eventType: undefined,
        enabled: undefined
      },
      eventTypeOptions: [
        { value: 'device_offline', label: '设备离线' },
        { value: 'device_line_abnormal', label: '外线异常' },
        { value: 'device_recovered', label: '故障恢复' },
        { value: 'manual_notify', label: '手动通知' }
      ],
      templateOptions: [],
      contactOptions: [],
      channelOptions: [],
      open: false,
      submitLoading: false,
      conditionSeverity: undefined,
      ruleForm: {
        id: undefined,
        ruleName: '',
        eventType: 'device_offline',
        conditionJson: '{}',
        receiverStrategyJson: '',
        channelsJson: '',
        templateId: undefined,
        enabled: 1,
        channels: [],
        contactIds: []
      },
      ruleRules: {
        ruleName: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
        eventType: [{ required: true, message: '请选择事件类型', trigger: 'change' }],
        channels: [{ required: true, type: 'array', message: '至少选择一个通道', trigger: 'change' }]
      }
    }
  },
  created() {
    this.getList()
    this.loadTemplates()
    this.loadContacts()
    this.loadChannels()
  },
  methods: {
    getList() {
      this.loading = true
      listRule(this.queryParams).then(response => {
        this.ruleList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    loadTemplates() {
      listTemplateOptions().then(list => {
        this.templateOptions = list
      })
    },
    loadContacts() {
      contactOptions().then(list => {
        this.contactOptions = list || []
      })
    },
    loadChannels() {
      channelOptions().then(list => {
        this.channelOptions = list || []
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
    handleAdd() {
      this.reset()
      this.open = true
      this.$nextTick(() => this.$refs.ruleForm && this.$refs.ruleForm.clearValidate())
    },
    handleUpdate(row) {
      this.reset()
      this.ruleForm.id = row.id
      this.ruleForm.ruleName = row.ruleName
      this.ruleForm.eventType = row.eventType
      this.ruleForm.templateId = row.templateId
      this.ruleForm.enabled = row.enabled
      this.ruleForm.channels = this.channels(row.channelsJson)
      this.ruleForm.contactIds = this.contactIds(row.receiverStrategyJson)
      try {
        const cond = JSON.parse(row.conditionJson || '{}')
        this.conditionSeverity = cond.severity || undefined
      } catch (e) {
        this.conditionSeverity = undefined
      }
      this.open = true
      this.$nextTick(() => this.$refs.ruleForm && this.$refs.ruleForm.clearValidate())
    },
    reset() {
      this.ruleForm = {
        id: undefined, ruleName: '', eventType: 'device_offline', conditionJson: '{}',
        receiverStrategyJson: '', channelsJson: '', templateId: undefined,
        enabled: 1, channels: [], contactIds: []
      }
      this.conditionSeverity = undefined
    },
    submitForm() {
      this.$refs.ruleForm.validate(valid => {
        if (!valid) return
        // 仅 pushplus 通道时允许不选接收人（按租户 token 群发）；否则必须选接收人
        const onlyPushplus = this.ruleForm.channels.length > 0 &&
          this.ruleForm.channels.every(cid => {
            const ch = this.channelOptions.find(o => o.id === cid)
            return ch && ch.channelType === 'pushplus'
          })
        if (!onlyPushplus && (!this.ruleForm.contactIds || this.ruleForm.contactIds.length === 0)) {
          this.$modal.msgError('请选择通知联系人（仅推送 PushPlus 通道时可不选）')
          return
        }
        this.ruleForm.conditionJson = this.conditionSeverity
          ? JSON.stringify({ severity: this.conditionSeverity })
          : '{}'
        this.ruleForm.receiverStrategyJson = JSON.stringify({
          type: 'fixed',
          contactIds: this.ruleForm.contactIds || []
        })
        this.ruleForm.channelsJson = JSON.stringify(this.ruleForm.channels)
        this.submitLoading = true
        const request = this.ruleForm.id ? updateRule(this.ruleForm) : addRule(this.ruleForm)
        request.then(() => {
          this.$modal.msgSuccess(this.ruleForm.id ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        }).finally(() => { this.submitLoading = false })
      })
    },
    handleEnabledChange(row) {
      updateRule(row).then(() => {
        this.$modal.msgSuccess(row.enabled === 1 ? '已启用' : '已停用')
      }).catch(() => {
        row.enabled = row.enabled === 1 ? 0 : 1
      })
    },
    handleDelete(row) {
      this.$modal.confirm('确认删除规则「' + row.ruleName + '」吗？').then(() => {
        return delRule(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('删除成功')
      }).catch(() => {})
    },
    channels(json) {
      try { return JSON.parse(json || '[]') } catch (e) { return [] }
    },
    channelName(cid) {
      const ch = this.channelOptions.find(o => o.id === cid)
      return ch ? ch.channelName : ('渠道#' + cid)
    },
    channelTypeName(t) {
      const map = { aliyun_sms: '阿里云短信', tencent_sms: '腾讯云短信', dingtalk: '钉钉', wechat_work: '企业微信群', wechat_app: '企业微信应用', feishu: '飞书', serverchan: 'Server酱', pushplus: 'PushPlus', email: '邮件', webhook: 'Webhook' }
      return map[t] || t
    },
    channelTagType(cid) {
      const ch = this.channelOptions.find(o => o.id === cid)
      if (!ch) return 'info'
      if (ch.channelType === 'pushplus') return 'warning'
      if (ch.channelType === 'aliyun_sms' || ch.channelType === 'tencent_sms') return 'danger'
      return 'success'
    },
    receivers(json) {
      try {
        const obj = JSON.parse(json || '{}')
        return obj.receivers || []
      } catch (e) { return [] }
    },
    contactIds(json) {
      try {
        const obj = JSON.parse(json || '{}')
        return obj.contactIds || []
      } catch (e) { return [] }
    },
    contactIdsText(json, channelsJson) {
      const ids = this.contactIds(json)
      if (ids.length === 0) {
        // 无联系人：若仅 pushplus 显示"按租户群发"
        const chs = this.channels(channelsJson)
        if (chs.length > 0 && chs.every(cid => {
          const ch = this.channelOptions.find(o => o.id === cid)
          return ch && ch.channelType === 'pushplus'
        })) {
          return '按租户 PushPlus 群发'
        }
        return '-'
      }
      const names = ids.map(id => {
        const c = this.contactOptions.find(o => o.id === id)
        return c ? c.name : ('联系人#' + id)
      })
      return names.join('、')
    },
    conditionText(json) {
      try {
        const cond = JSON.parse(json || '{}')
        if (cond.severity) return { critical: '仅高危', warning: '仅一般', resolved: '仅恢复' }[cond.severity] || cond.severity
        return '全部'
      } catch (e) { return '全部' }
    },
    eventTypeText(type) {
      const map = { device_offline: '设备离线', device_line_abnormal: '外线异常', device_recovered: '故障恢复', manual_notify: '手动通知' }
      return map[type] || type
    },
    eventTypeTag(type) {
      if (type === 'device_offline') return 'danger'
      if (type === 'device_line_abnormal') return 'warning'
      if (type === 'device_recovered') return 'success'
      return 'info'
    }
  }
}
</script>

<style scoped>
.mr-4 {
  margin-right: 4px;
}
.tip-text {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}
</style>
