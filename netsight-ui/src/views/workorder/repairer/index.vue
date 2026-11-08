<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="姓名/手机号" prop="keyword">
        <el-input v-model="queryParams.keyword" placeholder="姓名/手机号" clearable size="small"
                  style="width: 160px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="在岗状态" clearable size="small" style="width: 120px">
          <el-option label="在岗" :value="1" />
          <el-option label="休假" :value="0" />
          <el-option label="离职" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['workorder:repairer:add']" type="primary" plain icon="el-icon-plus"
                   size="mini" @click="handleAdd">新增维修人员</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table border v-loading="loading" :data="repairerList">
      <el-table-column label="姓名" prop="name" width="220" align="center">
        <template slot-scope="scope">
          <div>{{ scope.row.name }}</div>
          <el-tag size="mini" effect="plain" :type="typeInfo(scope.row.repairerType).tag">
            {{ typeInfo(scope.row.repairerType).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="手机号" prop="phone" width="195" align="center">
        <template slot-scope="scope">
          <div>{{ scope.row.phone || '-' }}</div>
          <div class="sub-line">
            <span v-if="scope.row.loginUsername">{{ scope.row.loginUsername }}</span>
            <span v-else class="muted">未绑定账号</span>
            <span v-if="scope.row.userStatus === 0" class="danger-text">已禁用</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="负责区域" prop="region" min-width="60" show-overflow-tooltip align="center" />
      <el-table-column label="设备类型" min-width="105" align="center">
        <template slot-scope="scope">
          <el-tag v-for="t in parseArr(scope.row.deviceTypes)" :key="t" size="mini"
                  style="margin-right:4px">{{ deviceTypeText(t) }}</el-tag>
          <span v-if="!scope.row.deviceTypes">-</span>
        </template>
      </el-table-column>
      <el-table-column label="技能" prop="skills" min-width="135" show-overflow-tooltip align="center" />
      <el-table-column label="在岗状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.status === 2" size="mini" type="danger">离职</el-tag>
          <el-switch v-else :value="scope.row.status === 1" @change="handleStatusChange(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="145" align="center" />
      <el-table-column label="操作" align="center" width="220" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasPermi="['workorder:repairer:edit']" size="mini" type="text"
                     icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-if="!scope.row.userId" v-hasPermi="['workorder:repairer:edit']" size="mini"
                     type="text" icon="el-icon-user" @click="handleBindAccount(scope.row)">补建账号</el-button>
          <el-button v-hasPermi="['workorder:repairer:remove']" size="mini" type="text"
                     icon="el-icon-delete" style="color:#f56c6c" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum"
                :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="form.id ? '修改维修人员' : '新增维修人员'" :visible.sync="open" width="620px"
               append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="人员类型">
          <el-tag v-if="form.id && form.repairerType === 0" type="info">未分类</el-tag>
          <el-radio-group v-else v-model="form.repairerType" :disabled="!!form.id"
                          @change="handleTypeChange">
            <el-radio :label="1">内聘（园区内部）</el-radio>
            <el-radio :label="2">外聘（外包）</el-radio>
          </el-radio-group>
          <div v-if="form.id && form.repairerType === 0" class="form-tip">
            请通过列表中的“补建账号”完成人员分类
          </div>
        </el-form-item>

        <el-form-item v-if="form.repairerType === 1" label="选择用户">
          <el-button size="small" type="primary" plain icon="el-icon-user"
                     :disabled="!!form.id" @click="openUserPicker">选择用户</el-button>
          <div v-if="form.userId" class="selected-user">
            {{ form.name }} / {{ form.phone }} / {{ form.username }}
          </div>
          <div v-else class="form-tip">从用户管理中选择本租户已启用且有手机号的用户</div>
        </el-form-item>

        <el-row>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="姓名" :disabled="form.repairerType !== 2" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="手机号" :disabled="form.repairerType !== 2" />
            </el-form-item>
          </el-col>
        </el-row>
        <div v-if="form.repairerType === 2 && !form.id" class="form-tip phone-tip">
          保存后自动创建登录账号，H5 用手机号 + 短信验证码登录
        </div>

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
            <el-radio :label="2">离职</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="补建账号" :visible.sync="bindOpen" width="420px" append-to-body>
      <el-radio-group v-model="bindType">
        <el-radio :label="1">内聘（绑定同手机号用户）</el-radio>
        <el-radio :label="2">外聘（创建新账号）</el-radio>
      </el-radio-group>
      <div class="form-tip">当前维修人员：{{ bindRow.name }} / {{ bindRow.phone }}</div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitBind">确 定</el-button>
        <el-button @click="bindOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <user-picker-dialog :visible.sync="userPickerVisible" @select="onUserSelected" />
  </div>
</template>

<script>
import { listRepairer, addRepairer, updateRepairer, delRepairer, bindRepairerAccount } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'
import UserPickerDialog from './components/UserPickerDialog'

export default {
  name: 'Repairer',
  components: { Pagination, UserPickerDialog },
  data() {
    return {
      loading: false,
      showSearch: true,
      repairerList: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, keyword: undefined, status: undefined },
      open: false,
      userPickerVisible: false,
      bindOpen: false,
      bindRow: {},
      bindType: 1,
      form: {},
      rules: {}
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRepairer(this.queryParams).then(response => {
        this.repairerList = response.rows || []
        this.total = response.total || 0
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
      this.form = { repairerType: 1, status: 1, deviceTypes: [], userId: null }
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row, deviceTypes: this.parseArr(row.deviceTypes), username: row.loginUsername }
      this.open = true
    },
    handleTypeChange() {
      if (this.form.id) return
      this.form.userId = null
      this.form.name = ''
      this.form.phone = ''
      this.form.username = ''
    },
    openUserPicker() {
      if (this.form.id) return
      this.userPickerVisible = true
    },
    onUserSelected(user) {
      this.form.userId = user.id
      this.form.name = user.realName
      this.form.phone = user.phone
      this.form.username = user.username
    },
    submitForm() {
      if (this.form.repairerType === 1 && !this.form.id && !this.form.userId) {
        this.$modal.msgWarning('请选择用户')
        return
      }
      if (this.form.repairerType === 2) {
        if (!this.form.name) {
          this.$modal.msgWarning('姓名不能为空')
          return
        }
        if (!/^1[3-9]\d{9}$/.test(this.form.phone || '')) {
          this.$modal.msgWarning('手机号格式不正确')
          return
        }
      }
      this.$refs['form'].validate(valid => {
        if (!valid) return
        const payload = {
          id: this.form.id,
          repairerType: this.form.repairerType,
          userId: this.form.userId,
          name: this.form.name,
          phone: this.form.phone,
          region: this.form.region,
          deviceTypes: JSON.stringify(this.form.deviceTypes || []),
          skills: this.form.skills,
          status: this.form.status,
          remark: this.form.remark
        }
        if (this.form.id) {
          updateRepairer(payload).then(() => {
            this.$modal.msgSuccess('修改成功')
            this.open = false
            this.getList()
          })
          return
        }
        addRepairer(payload).then(result => {
          if (result && result.accountCreated) {
            this.$modal.msgSuccess('维修人员已添加，登录账号：' + result.loginUsername)
          } else {
            this.$modal.msgSuccess('维修人员已添加')
          }
          this.open = false
          this.getList()
        })
      })
    },
    handleStatusChange(row) {
      if (row.status === 2) return
      const target = row.status === 1 ? 0 : 1
      updateRepairer({
        id: row.id,
        status: target,
        deviceTypes: JSON.stringify(this.parseArr(row.deviceTypes))
      }).then(() => {
        this.$modal.msgSuccess(target === 1 ? '已设为在岗' : '已设为休假')
        this.getList()
      })
    },
    handleBindAccount(row) {
      this.bindRow = row
      this.bindType = row.repairerType === 2 ? 2 : 1
      this.bindOpen = true
    },
    submitBind() {
      bindRepairerAccount(this.bindRow.id, { repairerType: this.bindType }).then(result => {
        this.$modal.msgSuccess(result && result.accountCreated ? '账号已创建并绑定' : '账号已绑定')
        this.bindOpen = false
        this.getList()
      })
    },
    handleDelete(row) {
      const message = row.repairerType === 2
        ? '确认删除维修人员 ' + row.name + '？其登录账号将同时被禁用。'
        : '确认删除维修人员 ' + row.name + '？用户在用户管理中的账号将保留。'
      this.$confirm(message, '警告', { type: 'warning' }).then(() => {
        delRepairer(row.id).then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
        })
      })
    },
    typeInfo(type) {
      if (type === 1) return { text: '内聘', tag: 'success' }
      if (type === 2) return { text: '外聘', tag: 'warning' }
      return { text: '未分类', tag: 'info' }
    },
    parseArr(value) {
      if (!value) return []
      try { return JSON.parse(value) } catch (e) { return [] }
    },
    deviceTypeText(type) {
      return { network: '网络设备', camera: '摄像头', nvr: 'NVR', door_controller: '门禁控制器' }[type] || type
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {}
    }
  }
}
</script>

<style scoped>
.sub-line {
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
}
.muted {
  color: #909399;
}
.danger-text {
  margin-left: 4px;
  color: #f56c6c;
}
.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.phone-tip {
  margin: -8px 0 12px 90px;
}
.selected-user {
  margin-top: 6px;
  font-size: 13px;
  color: #409eff;
}
</style>
