<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="姓名" prop="name">
        <el-input v-model="queryParams.name" placeholder="请输入姓名" clearable size="small" style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="手机号" prop="mobile">
        <el-input v-model="queryParams.mobile" placeholder="请输入手机号" clearable size="small" style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="状态" clearable size="small" style="width: 110px">
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
        <el-button v-hasPermi="['alert:contact:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增联系人</el-button>
      </el-col>
    </el-row>

    <!-- 列表 -->
    <el-table border v-loading="loading" :data="list">
      <el-table-column label="姓名" prop="name" min-width="120"  align="center"/>
      <el-table-column label="手机号" prop="mobile" min-width="130" align="center" />
      <el-table-column label="微信openid" min-width="170" show-overflow-tooltip align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.wechatOpenid">{{ maskOpenid(scope.row.wechatOpenid) }}</span>
          <el-tag v-else size="mini" type="info">未绑定</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.status === 1 ? 'success' : 'info'">{{ scope.row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="remark" min-width="160" show-overflow-tooltip  align="center"/>
      <el-table-column label="操作" align="center" width="240" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasPermi="['alert:contact:edit']" type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-if="!scope.row.wechatOpenid" v-hasPermi="['alert:contact:edit']" type="text" size="mini" icon="el-icon-chat-dot-round" @click="handleBindWechat(scope.row)">绑定微信</el-button>
          <el-button v-else v-hasPermi="['alert:contact:edit']" type="text" size="mini" icon="el-icon-close" @click="handleUnbindWechat(scope.row)">解绑</el-button>
          <el-button v-hasPermi="['alert:contact:remove']" type="text" size="mini" icon="el-icon-delete" class="el-button--text-danger" @click="handleDelete(scope.row)" style="color:#f56c6c">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="form.id ? '修改联系人' : '新增联系人'" :visible.sync="open" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="mobile">
          <el-input v-model="form.mobile" placeholder="短信通道投递地址，如 13800000000" maxlength="20" />
        </el-form-item>
        <el-form-item label="微信openid" prop="wechatOpenid">
          <el-input v-model="form.wechatOpenid" disabled placeholder="由“绑定微信”扫码写入，不支持手工修改" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" placeholder="可填所属部门/负责区域等" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确 定</el-button>
        <el-button @click="open = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 微信绑定二维码 -->
    <el-dialog title="绑定微信" :visible.sync="bindDialog" width="360px" append-to-body :close-on-click-modal="false" @closed="stopBindPolling">
      <div class="wechat-bind">
        <div ref="qrcode" class="wechat-qrcode"></div>
        <p class="wechat-bind-tip">请使用本人微信扫描二维码，关注服务号后即完成绑定。</p>
        <p v-if="bindStatusText" class="wechat-bind-status">{{ bindStatusText }}</p>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listContact, addContact, updateContact, delContact, getWechatBindUrl, getWechatBindStatus, unbindWechat } from '@/api/alert/contact'
import QRCode from 'qrcodejs2'
import Pagination from '@/components/Pagination'

export default {
  name: 'AlertContact',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      list: [],
      total: 0,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: undefined,
        mobile: undefined,
        status: undefined
      },
      open: false,
      submitLoading: false,
      bindDialog: false,
      bindUrl: '',
      bindTicket: '',
      bindStatusText: '',
      pollTimer: null,
      bindStartedAt: 0,
      form: {
        id: undefined,
        name: '',
        mobile: '',
        wechatOpenid: '',
        remark: '',
        status: 1
      },
      rules: {
        name: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
        mobile: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
  },
  beforeDestroy() {
    this.stopBindPolling()
  },
  methods: {
    maskOpenid(openid) {
      if (!openid) return ''
      if (openid.length <= 10) return openid.slice(0, 3) + '****'
      return openid.slice(0, 6) + '****' + openid.slice(-4)
    },
    getList() {
      this.loading = true
      listContact(this.queryParams).then(response => {
        this.list = (response.data && response.data.rows) || response.rows || []
        this.total = (response.data && response.data.total) || response.total || 0
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
    handleAdd() {
      this.reset()
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    handleUpdate(row) {
      this.reset()
      this.form = {
        id: row.id,
        name: row.name,
        mobile: row.mobile,
        wechatOpenid: row.wechatOpenid,
        remark: row.remark,
        status: row.status
      }
      this.open = true
      this.$nextTick(() => this.$refs.form && this.$refs.form.clearValidate())
    },
    reset() {
      this.form = { id: undefined, name: '', mobile: '', wechatOpenid: '', remark: '', status: 1 }
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        this.submitLoading = true
        const request = this.form.id ? updateContact(this.form) : addContact(this.form)
        request.then(() => {
          this.$modal.msgSuccess(this.form.id ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        }).finally(() => { this.submitLoading = false })
      })
    },
    handleDelete(row) {
      this.$modal.confirm('确认删除联系人「' + row.name + '」吗？').then(() => {
        return delContact(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('删除成功')
      }).catch(() => {})
    },
    handleBindWechat(row) {
      getWechatBindUrl(row.id).then(res => {
        const data = res.data || {}
        if (!data.url) {
          this.$modal.msgError('未获取到微信绑定地址，请检查公众号通道配置')
          return
        }
        this.bindUrl = data.url
        this.bindTicket = data.ticket || ''
        this.bindStatusText = '等待扫码...'
        this.bindDialog = true
        this.$nextTick(() => this.renderQrcode())
        this.startBindPolling()
      }).catch(() => {})
    },
    renderQrcode() {
      if (!this.$refs.qrcode) return
      this.$refs.qrcode.innerHTML = ''
      // eslint-disable-next-line no-new
      new QRCode(this.$refs.qrcode, { text: this.bindUrl, width: 220, height: 220 })
    },
    startBindPolling() {
      this.stopBindPolling()
      this.bindStartedAt = Date.now()
      if (!this.bindTicket) {
        this.bindStatusText = '二维码已生成，请扫码；若已完成请刷新列表'
        return
      }
      this.pollTimer = setInterval(() => {
        if (Date.now() - this.bindStartedAt > 5 * 60 * 1000) {
          this.stopBindPolling()
          this.bindStatusText = '二维码已过期，请关闭后重新生成'
          return
        }
        getWechatBindStatus(this.bindTicket).then(res => {
          const data = res.data || {}
          if (!data.status || data.status === 'PENDING') return
          this.stopBindPolling()
          if (data.bound) {
            this.bindDialog = false
            this.$modal.msgSuccess('微信绑定成功')
            this.getList()
          } else {
            this.bindStatusText = data.message || '绑定失败，请关闭后重试'
          }
        }).catch(() => {
          this.stopBindPolling()
          this.bindStatusText = '查询绑定结果失败，请稍后重试'
        })
      }, 2000)
    },
    stopBindPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    handleUnbindWechat(row) {
      this.$modal.confirm('确认解绑联系人「' + row.name + '」的微信吗？解绑后将无法收到微信告警。').then(() => {
        return unbindWechat(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess('已解绑微信')
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.wechat-bind {
  text-align: center;
}
.wechat-qrcode {
  display: inline-block;
  padding: 8px;
  background: #fff;
}
.wechat-bind-tip {
  margin: 12px 0 4px;
  color: #606266;
  font-size: 13px;
}
.wechat-bind-status {
  margin: 4px 0 0;
  color: #909399;
  font-size: 13px;
}
</style>
