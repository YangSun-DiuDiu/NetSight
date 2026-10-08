<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="登录账号" prop="username">
        <el-input
          v-model="queryParams.username"
          placeholder="请输入登录账号"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="手机号" prop="phone">
        <el-input
          v-model="queryParams.phone"
          placeholder="请输入手机号"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="用户状态" clearable size="small" style="width: 130px">
          <el-option label="正常" :value="1" />
          <el-option label="禁用" :value="0" />
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
        <el-button
          v-hasRole="['super_admin','tenant_admin']"
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
        >新增</el-button>
      </el-col>
    </el-row>

    <!-- 用户列表 -->
    <el-table border v-loading="loading" :data="userList">
      <el-table-column label="用户ID" prop="id" width="120"  align="center"/>
      <el-table-column label="登录账号" prop="username"  width="180" align="center"/>
      <el-table-column label="姓名" prop="realName"  width="180" align="center"/>
      <el-table-column label="手机号" prop="phone" width="180"  align="center"/>
      <el-table-column label="所属租户" prop="tenantId" min-width="180"  align="center"/>
      <el-table-column label="状态" align="center" width="180">
        <template slot-scope="scope">
          <el-switch
            v-hasRole="['super_admin','tenant_admin']"
            v-model="scope.row.status"
            :active-value="1"
            :inactive-value="0"
            :disabled="scope.row.username === 'admin'"
            @change="handleStatusChange(scope.row)"
          />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="200"  align="center"/>
      <el-table-column label="操作" align="center" width="180" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-key" @click="handleResetPwd(scope.row)">重置密码</el-button>
          <el-button
            v-hasRole="['super_admin','tenant_admin']"
            size="mini"
            type="text"
            icon="el-icon-delete"
            :disabled="scope.row.username === 'admin'"
            @click="handleDelete(scope.row)" style="color:#f56c6c">删除</el-button>
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
    <el-dialog :title="title" :visible.sync="open" width="560px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入登录账号" :disabled="form.id != null" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号（PC端登录使用）" maxlength="11" />
        </el-form-item>
        <el-form-item label="所属租户" prop="tenantId" v-if="isSuperAdmin">
          <el-select v-model="form.tenantId" placeholder="选择租户" style="width: 100%">
            <el-option
              v-for="item in tenantOptions"
              :key="item.id"
              :label="item.tenantName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-select v-model="form.roleIds" multiple placeholder="请选择角色" style="width: 100%">
            <el-option
              v-for="item in roleOptions"
              :key="item.id"
              :label="item.roleName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">正常</el-radio>
            <el-radio :label="0">禁用</el-radio>
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
import { listUser, addUser, updateUser, delUser, resetUserPwd, changeUserStatus, getUserRoleIds } from "@/api/system/user"
import { listTenant } from "@/api/system/tenant"
import { allRole } from "@/api/system/role"

export default {
  name: "User",
  data() {
    return {
      loading: true,
      showSearch: true,
      userList: [],
      total: 0,
      title: "",
      open: false,
      isSuperAdmin: false,
      roleOptions: [],
      tenantOptions: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        username: undefined,
        phone: undefined,
        status: undefined
      },
      form: {},
      rules: {
        username: [{ required: true, message: "登录账号不能为空", trigger: "blur" }],
        realName: [{ required: true, message: "姓名不能为空", trigger: "blur" }],
        phone: [
          { required: true, message: "手机号不能为空", trigger: "blur" },
          { pattern: /^1[3-9]\d{9}$/, message: "手机号格式不正确", trigger: "blur" }
        ],
        roleIds: [{ required: true, message: "请选择角色", trigger: "change" }]
      }
    }
  },
  created() {
    this.isSuperAdmin = this.$store.getters.roles.includes("super_admin")
    this.getList()
    this.getRoleOptions()
    if (this.isSuperAdmin) {
      this.getTenantOptions()
    }
  },
  methods: {
    getList() {
      this.loading = true
      listUser(this.queryParams).then(response => {
        this.userList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    getRoleOptions() {
      allRole().then(data => {
        this.roleOptions = data
      })
    },
    getTenantOptions() {
      listTenant({ pageNum: 1, pageSize: 100 }).then(response => {
        this.tenantOptions = response.rows
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
      this.title = "新增用户"
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row, roleIds: [] }
      // 回显角色
      getUserRoleIds(row.id).then(roleIds => {
        this.$set(this.form, 'roleIds', roleIds || [])
      })
      this.open = true
      this.title = "修改用户"
    },
    handleStatusChange(row) {
      changeUserStatus(row.id, row.status).then(() => {
        this.$modal.msgSuccess("状态修改成功")
      }).catch(() => {
        this.getList()
      })
    },
    handleResetPwd(row) {
      this.$modal.confirm('是否确认重置用户"' + row.username + '"的密码？重置后为默认密码 admin123').then(() => {
        return resetUserPwd(row.id)
      }).then(() => {
        this.$modal.msgSuccess("重置成功")
      }).catch(() => {})
    },
    handleDelete(row) {
      this.$modal.confirm('是否确认删除用户"' + row.username + '"？').then(() => {
        return delUser(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateUser(this.form).then(() => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addUser(this.form).then(() => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    reset() {
      this.form = {
        id: undefined,
        username: undefined,
        realName: undefined,
        phone: undefined,
        tenantId: this.isSuperAdmin ? undefined : this.$store.getters.tenantId,
        roleIds: [],
        status: 1
      }
    },
    cancel() {
      this.open = false
      this.reset()
    }
  }
}
</script>
