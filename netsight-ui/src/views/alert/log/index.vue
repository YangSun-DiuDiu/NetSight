<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="事件类型" prop="eventType">
        <el-input v-model="queryParams.eventType" placeholder="事件类型" clearable size="small" style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="通道" prop="channelType">
        <el-select v-model="queryParams.channelType" placeholder="通知通道" clearable size="small" style="width: 140px">
          <el-option v-for="t in channelTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="结果" prop="success">
        <el-select v-model="queryParams.success" placeholder="发送结果" clearable size="small" style="width: 120px">
          <el-option label="成功" :value="1" />
          <el-option label="失败" :value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 日志列表 -->
    <el-table border v-loading="loading" :data="logList">
      <el-table-column label="ID" prop="id" width="70" align="center" />
      <el-table-column label="事件ID" prop="eventId" width="70" align="center">
        <template slot-scope="scope">{{ scope.row.eventId || '-' }}</template>
      </el-table-column>
      <el-table-column label="事件类型" prop="eventType" width="130"  align="center"/>
      <el-table-column label="匹配规则" prop="ruleName" min-width="130" show-overflow-tooltip align="center">
        <template slot-scope="scope">{{ scope.row.ruleName || '-' }}</template>
      </el-table-column>
      <el-table-column label="通道" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="channelTagType(scope.row.channelType)">{{ channelTypeName(scope.row.channelType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="接收人" min-width="150" show-overflow-tooltip align="center"><template slot-scope="scope">{{ receiversText(scope.row.receiversJson) }}</template></el-table-column>
      <el-table-column label="发送内容" prop="content" min-width="180" show-overflow-tooltip  align="center"/>
      <el-table-column label="结果" width="80" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.success === 1 ? 'success' : 'danger'">{{ scope.row.success === 1 ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="第三方ID" prop="thirdPartyMsgId" width="140" show-overflow-tooltip  align="center"/>
      <el-table-column label="耗时" width="80" align="center">
        <template slot-scope="scope">{{ scope.row.costTime || 0 }} ms</template>
      </el-table-column>
      <el-table-column label="发送时间" prop="createTime" width="160" align="center" />
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
  </div>
</template>

<script>
import { listLog } from '@/api/alert/log'
import Pagination from '@/components/Pagination'

export default {
  name: 'AlertLog',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      logList: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        eventType: undefined,
        channelType: undefined,
        success: undefined
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
      ]
    }
  },
  created() {
    this.getList()
  },
  methods: {
    receiversText(json) {
      if (!json) return '-'
      try {
        const arr = JSON.parse(json)
        if (Array.isArray(arr)) return arr.length ? arr.join('、') : '-'
        if (typeof arr === 'object' && arr) return arr.receivers ? arr.receivers.join('、') : json
        return String(arr)
      } catch (e) { return json }
    },
    channelTypeName(t) {
      const o = this.channelTypeOptions.find(x => x.value === t)
      return o ? o.label : t
    },
    channelTagType(t) {
      if (t === 'pushplus' || t === 'serverchan') return 'warning'
      if (t === 'aliyun_sms' || t === 'tencent_sms' || t === 'sms') return 'danger'
      return 'success'
    },
    getList() {
      this.loading = true
      listLog(this.queryParams).then(response => {
        this.logList = response.rows
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
    }
  }
}
</script>
