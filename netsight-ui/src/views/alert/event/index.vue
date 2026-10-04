<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="事件类型" prop="eventType">
        <el-select v-model="queryParams.eventType" placeholder="事件类型" clearable size="small" style="width: 180px">
          <el-option v-for="dict in eventTypeOptions" :key="dict.value" :label="dict.label" :value="dict.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="级别" prop="severity">
        <el-select v-model="queryParams.severity" placeholder="级别" clearable size="small" style="width: 120px">
          <el-option label="高危" value="critical" />
          <el-option label="一般" value="warning" />
          <el-option label="已恢复" value="resolved" />
          <el-option label="信息" value="info" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="处理状态" clearable size="small" style="width: 120px">
          <el-option label="待处理" value="pending" />
          <el-option label="处理中" value="processing" />
          <el-option label="已发送" value="sent" />
          <el-option label="部分失败" value="part_failed" />
          <el-option label="失败" value="failed" />
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
        <el-button v-hasPermi="['alert:event:manual']" type="primary" plain icon="el-icon-position" size="mini" @click="handleManual">手动发送</el-button>
      </el-col>
    </el-row>

    <!-- 事件列表 -->
    <el-table border v-loading="loading" :data="eventList">
      <el-table-column label="ID" prop="id" width="70" align="center" />
      <el-table-column label="事件类型" width="140" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="eventTypeTag(scope.row.eventType)">{{ eventTypeText(scope.row.eventType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="80" align="center">
        <template slot-scope="scope">
          <span class="sev-badge" :style="{ background: severityColor(scope.row.severity) }">
            {{ severityText(scope.row.severity) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="设备" min-width="150" align="center">
        <template slot-scope="scope">{{ scope.row.deviceName }} <span class="el-text-muted" v-if="scope.row.deviceIp">({{ scope.row.deviceIp }})</span></template>
      </el-table-column>
      <el-table-column label="设备位置" prop="location" min-width="120" show-overflow-tooltip  align="center"/>
      <el-table-column label="匹配规则" prop="ruleName" min-width="130" show-overflow-tooltip align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.ruleName">{{ scope.row.ruleName }}</span>
          <span v-else class="el-text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="发送" width="90" align="center">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.logCount > 0" size="mini" type="info">{{ scope.row.logCount }} 条</el-tag>
          <span v-else class="el-text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusTag(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发生时间" prop="createTime" width="160" align="center" />
      <el-table-column label="操作" align="center" width="110" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button type="text" size="mini" icon="el-icon-view" @click="handleDetail(scope.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 手动发送弹窗 -->
    <el-dialog title="手动发送通知" :visible.sync="manualOpen" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="manualForm" :model="manualForm" :rules="manualRules" label-width="90px">
        <el-form-item label="通知内容" prop="content">
          <el-input v-model="manualForm.content" type="textarea" :rows="4" placeholder="请输入通知内容（如：周末机房巡检通知）" />
        </el-form-item>
        <el-form-item label="接收人" prop="contactIds">
          <el-select v-model="manualForm.contactIds" multiple filterable placeholder="选择通知联系人" style="width: 100%">
            <el-option v-for="c in contactOptions" :key="c.id" :label="c.name + '（' + (c.mobile || '未填手机号') + '）'" :value="c.id" />
          </el-select>
          <div class="tip-text">短信/公众号按所选联系人发送；仅推送(PushPlus)可不选（按租户 Token 群发）。</div>
        </el-form-item>
        <el-form-item label="通知通道" prop="channels">
          <el-select v-model="manualForm.channels" multiple filterable placeholder="选择通知渠道实例（可多选）" style="width: 100%">
            <el-option v-for="ch in channelOptions" :key="ch.id" :label="ch.channelName + '（' + channelTypeName(ch.channelType) + '）'" :value="ch.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="manualLoading" @click="submitManual">发 送</el-button>
        <el-button @click="manualOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 事件详情弹窗 -->
    <el-dialog title="事件详情" :visible.sync="detailOpen" width="620px" append-to-body :close-on-click-modal="false">
      <el-descriptions :column="2" border size="mini" v-if="detailRow">
        <el-descriptions-item label="事件ID">{{ detailRow.id }}</el-descriptions-item>
        <el-descriptions-item label="事件类型">{{ eventTypeText(detailRow.eventType) }}</el-descriptions-item>
        <el-descriptions-item label="级别">{{ severityText(detailRow.severity) }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceText(detailRow.eventSource) }}</el-descriptions-item>
        <el-descriptions-item label="设备">{{ detailRow.deviceName }}（{{ detailRow.deviceIp }}）</el-descriptions-item>
        <el-descriptions-item label="设备类型">{{ detailRow.deviceType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="位置" :span="2">{{ detailRow.location || '-' }}</el-descriptions-item>
        <el-descriptions-item label="业务ID" :span="2">{{ detailRow.bizId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="匹配规则" :span="2">{{ detailRow.ruleName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态" :span="2">{{ statusText(detailRow.status) }}</el-descriptions-item>
        <el-descriptions-item label="发生时间" :span="2">{{ detailRow.createTime }}</el-descriptions-item>
        <el-descriptions-item label="通知内容" :span="2">
          <pre class="detail-content">{{ detailRow.content || '-' }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script>
import { listEvent, manualSend } from '@/api/alert/event'
import { contactOptions } from '@/api/alert/contact'
import { channelOptions } from '@/api/alert/channel'
import Pagination from '@/components/Pagination'

export default {
  name: 'AlertEvent',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      eventList: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        eventType: undefined,
        severity: undefined,
        status: undefined
      },
      eventTypeOptions: [
        { value: 'device_offline', label: '设备离线' },
        { value: 'device_line_abnormal', label: '外线异常' },
        { value: 'device_recovered', label: '故障恢复' },
        { value: 'manual_notify', label: '手动通知' }
      ],
      manualOpen: false,
      manualLoading: false,
      contactOptions: [],
      channelOptions: [],
      manualForm: {
        content: '',
        contactIds: [],
        channels: []
      },
      manualRules: {
        content: [{ required: true, message: '通知内容不能为空', trigger: 'blur' }],
        channels: [{ required: true, type: 'array', message: '至少选择一个通道', trigger: 'change' }]
      },
      detailOpen: false,
      detailRow: null
    }
  },
  created() {
    this.getList()
    this.loadContacts()
    this.loadChannels()
  },
  methods: {
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
    channelTypeName(t) {
      const map = { aliyun_sms: '阿里云短信', tencent_sms: '腾讯云短信', dingtalk: '钉钉', wechat_work: '企业微信群', wechat_app: '企业微信应用', feishu: '飞书', serverchan: 'Server酱', pushplus: 'PushPlus', email: '邮件', webhook: 'Webhook' }
      return map[t] || t
    },
    getList() {
      this.loading = true
      listEvent(this.queryParams).then(response => {
        this.eventList = response.rows
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
    handleManual() {
      this.manualForm = { content: '', contactIds: [], channels: [] }
      this.manualOpen = true
      this.$nextTick(() => this.$refs.manualForm && this.$refs.manualForm.clearValidate())
    },
    submitManual() {
      this.$refs.manualForm.validate(valid => {
        if (!valid) return
        // 仅 pushplus 通道时允许不选联系人；否则必须选
        const onlyPushplus = this.manualForm.channels.length > 0 &&
          this.manualForm.channels.every(cid => {
            const ch = this.channelOptions.find(o => o.id === cid)
            return ch && ch.channelType === 'pushplus'
          })
        if (!onlyPushplus && (!this.manualForm.contactIds || this.manualForm.contactIds.length === 0)) {
          this.$modal.msgError('请选择通知联系人（仅推送 PushPlus 通道时可不选）')
          return
        }
        this.manualLoading = true
        manualSend({
          eventType: 'manual_notify',
          content: this.manualForm.content,
          contactIds: this.manualForm.contactIds || [],
          channels: this.manualForm.channels
        }).then(() => {
          this.$modal.msgSuccess('通知已发送')
          this.manualOpen = false
          this.getList()
        }).finally(() => { this.manualLoading = false })
      })
    },
    handleDetail(row) {
      this.detailRow = row
      this.detailOpen = true
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
    },
    severityText(sev) {
      return { critical: '高危', warning: '一般', resolved: '已恢复', info: '信息' }[sev] || sev
    },
    severityColor(sev) {
      return { critical: '#f56c6c', warning: '#e6a23c', resolved: '#67c23a', info: '#409eff' }[sev] || '#909399'
    },
    statusText(status) {
      return { pending: '待处理', processing: '处理中', sent: '已发送', part_failed: '部分失败', failed: '失败' }[status] || status
    },
    statusTag(status) {
      return { sent: 'success', processing: 'primary', part_failed: 'warning', failed: 'danger', pending: 'info' }[status] || 'info'
    },
    sourceText(src) {
      return { alertmanager: '自动告警', manual: '手动发送', system: '系统' }[src] || src
    }
  }
}
</script>

<style scoped>
.sev-badge {
  display: inline-block;
  min-width: 46px;
  padding: 2px 8px;
  border-radius: 3px;
  color: #fff;
  font-size: 12px;
}
.detail-content {
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}
.tip-text {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}
</style>
