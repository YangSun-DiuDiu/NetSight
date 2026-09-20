<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="租户名称" prop="tenantName">
        <el-input
          v-model="queryParams.tenantName"
          placeholder="请输入租户名称"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          v-hasRole="['super_admin']"
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
        >新增</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="tenantList">
      <el-table-column label="租户ID" prop="id" width="80" />
      <el-table-column label="租户名称" prop="tenantName" />
      <el-table-column label="联系人" prop="contactPerson" />
      <el-table-column label="联系电话" prop="contactPhone" />
      <el-table-column label="Webhook Token" prop="webhookToken" min-width="180">
        <template slot-scope="scope">
          <span>{{ scope.row.webhookToken }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="80">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === 1 ? 'success' : 'danger'">
            {{ scope.row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="到期时间" prop="expireTime" width="160" />
      <el-table-column label="操作" align="center" width="300" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-key" @click="handleToken(scope.row)">Token</el-button>
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-bell" @click="handlePpToken(scope.row)">推送Token</el-button>
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
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
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="租户名称" prop="tenantName">
          <el-input v-model="form.tenantName" placeholder="请输入租户名称" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="到期时间" prop="expireTime">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            placeholder="选择到期时间"
            value-format="yyyy-MM-dd HH:mm:ss"
          />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- Webhook Token 查看/重置弹窗 -->
    <el-dialog :title="tokenTitle" :visible.sync="tokenOpen" width="560px" append-to-body :close-on-click-modal="false">
      <el-alert
        title="Token 即租户身份：边缘网关 AlertManager 推送告警时携带该 Token，云端据此反查租户归属（方案 5.5 第 7 小节）。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
      />
      <el-form label-width="100px">
        <el-form-item label="租户名称">
          <span>{{ tokenTenantName }}</span>
        </el-form-item>
        <el-form-item label="Webhook Token">
          <el-input v-model="tokenValue" readonly>
            <el-button slot="append" icon="el-icon-document-copy" @click="copyToken">复制</el-button>
          </el-input>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button v-hasRole="['super_admin']" type="warning" icon="el-icon-refresh" @click="handleResetToken">重置 Token</el-button>
        <el-button type="primary" @click="tokenOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- PushPlus Token 配置弹窗 -->
    <el-dialog title="PushPlus Token · " :visible.sync="ppTokenOpen" width="560px" append-to-body :close-on-click-modal="false">
      <el-alert
        title="PushPlus 用于微信推送：在 pushplus.plus 注册并完成实名认证后，把你的 token 填到这里。配置后，该租户选择「推送(PushPlus)」通道的通知会自动推送给所有已关注公众号的人，无需为每个接收人单独配置。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
      />
      <el-form label-width="110px">
        <el-form-item label="租户名称">
          <span>{{ ppTenantName }}</span>
        </el-form-item>
        <el-form-item label="当前 Token">
          <el-input v-model="ppToken" readonly>
            <el-button slot="append" icon="el-icon-document-copy" @click="copyPpToken">复制</el-button>
          </el-input>
        </el-form-item>
        <el-form-item label="配置/更新 Token">
          <el-input v-model="ppInput" placeholder="粘贴 pushplus 的 token，留空点清空则停用推送通道" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" icon="el-icon-check" @click="savePpToken">保存配置</el-button>
        <el-button type="warning" icon="el-icon-delete" @click="clearPpToken">清空 Token</el-button>
        <el-button @click="ppTokenOpen = false">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTenant, addTenant, updateTenant, delTenant, getWebhookToken, resetWebhookToken, getPushplusToken, setPushplusToken, clearPushplusToken } from "@/api/system/tenant"

export default {
  name: "Tenant",
  data() {
    return {
      loading: true,
      showSearch: true,
      tenantList: [],
      total: 0,
      title: "",
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        tenantName: undefined
      },
      form: {},
      rules: {
        tenantName: [{ required: true, message: "租户名称不能为空", trigger: "blur" }]
      },
      // Webhook Token 弹窗
      tokenOpen: false,
      tokenTitle: "Webhook Token",
      tokenTenantName: "",
      tokenValue: "",
      tokenTenantId: undefined,
      // PushPlus Token 弹窗
      ppTokenOpen: false,
      ppTokenId: undefined,
      ppTenantName: "",
      ppToken: "",
      ppInput: ""
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listTenant(this.queryParams).then(response => {
        this.tenantList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "新增租户"
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row }
      this.open = true
      this.title = "修改租户"
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            // 修改：Token 只能走重置接口，禁止随表单提交覆盖
            delete this.form.webhookToken
            delete this.form.webhookTokenTime
            updateTenant(this.form).then(() => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addTenant(this.form).then(response => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
              // 新增租户自动生成 Webhook Token，一次性弹窗展示
              if (response && response.data) {
                this.tokenTenantName = this.form.tenantName
                this.tokenValue = response.data
                this.tokenTitle = "新增成功 · Webhook Token（请立即保存）"
                this.tokenOpen = true
              }
            })
          }
        }
      })
    },
    handleDelete(row) {
      this.$modal.confirm('是否确认删除租户"' + row.tenantName + '"？删除后其 Webhook Token 同步失效。').then(() => {
        return delTenant(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    // ---- Webhook Token 弹窗 ----
    handleToken(row) {
      this.tokenTenantId = row.id
      this.tokenTenantName = row.tenantName
      this.tokenTitle = "Webhook Token · " + row.tenantName
      this.tokenValue = "加载中..."
      this.tokenOpen = true
      getWebhookToken(row.id).then(token => {
        this.tokenValue = token
      }).catch(() => {
        this.tokenValue = "获取失败"
      })
    },
    handleResetToken() {
      this.$modal.confirm('重置后旧 Token 立即失效，已配置该 Token 的边缘网关需同步更新。是否确认重置？').then(() => {
        return resetWebhookToken(this.tokenTenantId)
      }).then(token => {
        this.tokenValue = token
        this.$modal.msgSuccess("重置成功，旧 Token 已失效")
        this.getList()
      }).catch(() => {})
    },
    copyToken() {
      if (!this.tokenValue || this.tokenValue === "加载中..." || this.tokenValue === "获取失败") {
        this.$modal.msgWarning("暂无有效的 Token 可复制")
        return
      }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(this.tokenValue).then(() => {
          this.$modal.msgSuccess("已复制到剪贴板")
        }).catch(() => {
          this.copyFallback(this.tokenValue)
        })
      } else {
        this.copyFallback(this.tokenValue)
      }
    },
    copyFallback(text) {
      const input = document.createElement("input")
      input.value = text
      document.body.appendChild(input)
      input.select()
      try {
        document.execCommand("copy")
        this.$modal.msgSuccess("已复制到剪贴板")
      } catch (e) {
        this.$modal.msgError("复制失败，请手动选择复制")
      }
      document.body.removeChild(input)
    },
    // ---- PushPlus Token 弹窗 ----
    handlePpToken(row) {
      this.ppTokenId = row.id
      this.ppTenantName = row.tenantName
      this.ppInput = ""
      this.ppTokenOpen = true
      this.ppToken = "加载中..."
      getPushplusToken(row.id).then(token => {
        this.ppToken = token || "（未配置）"
      }).catch(() => {
        this.ppToken = "获取失败"
      })
    },
    savePpToken() {
      if (!this.ppInput) {
        this.$modal.msgWarning("请输入 pushplus token")
        return
      }
      setPushplusToken(this.ppTokenId, this.ppInput).then(token => {
        this.ppToken = token || this.ppInput
        this.ppInput = ""
        this.$modal.msgSuccess("PushPlus Token 已配置")
        this.getList()
      })
    },
    clearPpToken() {
      this.$modal.confirm("清空后该租户的「推送(PushPlus)」通道将停用，是否确认？").then(() => {
        return clearPushplusToken(this.ppTokenId)
      }).then(() => {
        this.ppToken = "（未配置）"
        this.ppInput = ""
        this.$modal.msgSuccess("已清空")
        this.getList()
      }).catch(() => {})
    },
    copyPpToken() {
      if (!this.ppToken || this.ppToken === "加载中..." || this.ppToken === "获取失败" || this.ppToken === "（未配置）") {
        this.$modal.msgWarning("暂无可复制的 Token")
        return
      }
      this.copyFallback(this.ppToken)
    },
    reset() {
      this.form = {
        id: undefined,
        tenantName: undefined,
        contactPerson: undefined,
        contactPhone: undefined,
        status: 1,
        expireTime: undefined
      }
    },
    cancel() {
      this.open = false
      this.reset()
    }
  }
}
</script>
