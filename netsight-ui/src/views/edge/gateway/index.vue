<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="网关名称" prop="gatewayName">
        <el-input
          v-model="queryParams.gatewayName"
          placeholder="请输入网关名称"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="在线状态" prop="onlineStatus">
        <el-select v-model="queryParams.onlineStatus" placeholder="在线状态" clearable size="small" style="width: 130px">
          <el-option label="在线" :value="1" />
          <el-option label="离线" :value="0" />
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
        <el-button v-hasRole="['super_admin','tenant_admin']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增网关</el-button>
      </el-col>
    </el-row>

    <!-- 网关列表 -->
    <el-table border v-loading="loading" :data="gatewayList">
      <el-table-column label="网关名称" prop="gatewayName" width="200"  align="center"/>
      <el-table-column label="网关编码" prop="gatewayCode" width="200"  align="center"/>
      <el-table-column label="部署位置" prop="location" min-width="120" show-overflow-tooltip  align="center"/>
      <el-table-column label="所属租户" prop="tenantId" width="200"  align="center"/>
      <el-table-column label="上行链路" width="200" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="linkTypeTag(scope.row.linkType)">{{ linkTypeText(scope.row.linkType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近IP" prop="ipAddress" width="200"  align="center"/>
      <el-table-column label="在线状态" width="200" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.onlineStatus === 1 ? 'success' : 'danger'">
            {{ scope.row.onlineStatus === 1 ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近心跳" prop="lastHeartbeatTime" width="200"  align="center"/>
      <el-table-column label="PushPlus" width="200" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.pushplusToken ? 'success' : 'info'">
            {{ scope.row.pushplusToken ? scope.row.pushplusToken : '未配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="200" align="center">
        <template slot-scope="scope">
          <el-switch v-hasRole="['super_admin','tenant_admin']" v-model="scope.row.status" :active-value="1" :inactive-value="0" @change="handleStatusChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="220" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-key" @click="handleResetToken(scope.row)">重置Token</el-button>
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-chat-dot-round" @click="handlePp(scope.row)">PushPlus</el-button>
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" style="color:#f56c6c">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="title" :visible.sync="open" width="520px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="网关名称" prop="gatewayName">
          <el-input v-model="form.gatewayName" placeholder="请输入网关名称" />
        </el-form-item>
        <el-form-item label="部署位置" prop="location">
          <el-input v-model="form.location" placeholder="如：一号厂房弱电间" />
        </el-form-item>
        <el-form-item label="所属租户" prop="tenantId" v-if="isSuperAdmin">
          <el-select v-model="form.tenantId" placeholder="选择租户" style="width: 100%">
            <el-option v-for="item in tenantOptions" :key="item.id" :label="item.tenantName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="上行链路" prop="linkType">
          <el-radio-group v-model="form.linkType">
            <el-radio label="wired">有线</el-radio>
            <el-radio label="wifi">WiFi</el-radio>
            <el-radio label="4g5g">4G/5G</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 新网关 Token 提示 -->
    <el-dialog title="网关接入信息" :visible.sync="tokenDialog" width="480px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="success"
        :closable="false"
        title="请妥善保存以下网关接入Token（仅本次展示，后续可在操作列重置）"
        style="margin-bottom: 12px"
      />
      <el-input v-model="newToken" readonly>
        <template slot="prepend">X-Gateway-Token</template>
      </el-input>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="tokenDialog = false">我已保存</el-button>
      </div>
    </el-dialog>

    <!-- PushPlus Token 配置 -->
    <el-dialog title="PushPlus Token 配置" :visible.sync="ppDialog" width="520px" append-to-body :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        title="该网关产生的告警将优先使用本网关的 PushPlus Token 推送；未配置时自动沿用租户级 Token。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="当前Token">
          <el-input v-model="ppMasked" readonly placeholder="未配置">
            <template slot="append">
              <el-button :disabled="!ppMasked" @click="handlePpCopy">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="新Token">
          <el-input v-model="ppToken" placeholder="输入新的 PushPlus Token；留空保存即清空网关级配置" show-password />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button :disabled="!ppMasked" @click="handlePpClear">清空</el-button>
        <el-button type="primary" @click="handlePpSave">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listGateway, addGateway, updateGateway, delGateway, resetGatewayToken, getGatewayPushplusToken, setGatewayPushplusToken } from '@/api/edge/gateway'
import { listTenant } from '@/api/system/tenant'

export default {
  name: 'Gateway',
  data() {
    return {
      loading: false,
      showSearch: true,
      gatewayList: [],
      tenantOptions: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, gatewayName: undefined, onlineStatus: undefined },
      title: '',
      open: false,
      tokenDialog: false,
      newToken: '',
      ppDialog: false,
      ppRow: null,
      ppMasked: '',
      ppToken: '',
      form: {},
      rules: {
        gatewayName: [{ required: true, message: '网关名称不能为空', trigger: 'blur' }],
        linkType: [{ required: true, message: '请选择上行链路', trigger: 'change' }]
      }
    }
  },
  computed: {
    isSuperAdmin() {
      return this.$store.getters.roles.includes('super_admin')
    }
  },
  created() {
    this.getList()
    if (this.isSuperAdmin) {
      this.getTenantOptions()
    }
  },
  methods: {
    getList() {
      this.loading = true
      listGateway(this.queryParams).then(res => {
        this.gatewayList = res.rows
        this.total = res.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    getTenantOptions() {
      listTenant({ pageNum: 1, pageSize: 100 }).then(res => {
        this.tenantOptions = res.rows
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
      this.title = '新增网关'
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = { id: row.id, gatewayName: row.gatewayName, location: row.location, tenantId: row.tenantId, linkType: row.linkType, status: row.status }
      this.title = '修改网关'
      this.open = true
    },
    handleStatusChange(row) {
      const text = row.status === 1 ? '启用' : '禁用'
      updateGateway({ id: row.id, gatewayName: row.gatewayName, location: row.location, tenantId: row.tenantId, linkType: row.linkType, status: row.status })
        .then(() => this.$modal.msgSuccess(text + '成功'))
        .catch(() => { row.status = row.status === 1 ? 0 : 1 })
    },
    handleResetToken(row) {
      this.$modal.confirm('重置后旧Token立即失效，网关需重新配置接入Token。是否继续？').then(() => {
        resetGatewayToken(row.id).then(res => {
          this.newToken = res.data.gatewayToken
          this.tokenDialog = true
          this.$modal.msgSuccess('Token已重置')
        })
      }).catch(() => {})
    },
    handlePp(row) {
      this.ppRow = row
      this.ppToken = ''
      this.ppMasked = ''
      this.ppDialog = true
      getGatewayPushplusToken(row.id).then(res => {
        this.ppMasked = res.data.pushplusToken || ''
      })
    },
    handlePpSave() {
      setGatewayPushplusToken(this.ppRow.id, this.ppToken).then(() => {
        this.$modal.msgSuccess(this.ppToken ? 'PushPlus Token 已保存' : '网关级配置已清空（沿用租户级）')
        this.ppDialog = false
        this.getList()
      })
    },
    handlePpClear() {
      this.$modal.confirm('确认清空该网关的 PushPlus Token？清空后将沿用租户级 Token。').then(() => {
        setGatewayPushplusToken(this.ppRow.id, '').then(() => {
          this.$modal.msgSuccess('已清空（沿用租户级 Token）')
          this.ppDialog = false
          this.getList()
        })
      }).catch(() => {})
    },
    handlePpCopy() {
      const input = document.createElement('input')
      input.value = this.ppMasked
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
      this.$modal.msgSuccess('已复制（脱敏值，完整 Token 仅配置时可查看）')
    },
    handleDelete(row) {
      this.$modal.confirm('确认删除网关【' + row.gatewayName + '】？网关下存在设备时禁止删除。').then(() => {
        delGateway(row.id).then(() => {
          this.getList()
          this.$modal.msgSuccess('删除成功')
        })
      }).catch(() => {})
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateGateway(this.form).then(() => {
              this.$modal.msgSuccess('修改成功')
              this.open = false
              this.getList()
            })
          } else {
            addGateway(this.form).then(res => {
              this.$modal.msgSuccess('新增成功')
              this.open = false
              this.getList()
              if (res.data && res.data.gatewayToken) {
                this.newToken = res.data.gatewayToken
                this.tokenDialog = true
              }
            })
          }
        }
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = { gatewayName: undefined, location: undefined, tenantId: undefined, linkType: 'wired', status: 1 }
      this.resetForm('form')
    },
    linkTypeText(type) {
      return { wired: '有线', wifi: 'WiFi', '4g5g': '4G/5G' }[type] || type
    },
    linkTypeTag(type) {
      return { wired: '', wifi: 'warning', '4g5g': 'info' }[type] || 'info'
    }
  }
}
</script>
