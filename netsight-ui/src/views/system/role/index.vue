<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="角色名称" prop="roleName">
        <el-input
          v-model="queryParams.roleName"
          placeholder="请输入角色名称"
          clearable
          size="small"
          style="width: 200px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="角色编码" prop="roleKey">
        <el-input
          v-model="queryParams.roleKey"
          placeholder="请输入角色编码"
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

    <!-- 操作按钮区 -->
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

    <!-- 角色列表 -->
    <el-table border v-loading="loading" :data="roleList">
      <el-table-column label="角色ID" prop="id" width="80"  align="center"/>
      <el-table-column label="角色名称" prop="roleName"  align="center"/>
      <el-table-column label="角色编码" prop="roleKey"  align="center"/>
      <el-table-column label="显示顺序" prop="roleSort" width="90" align="center" />
      <el-table-column label="状态" align="center" width="100">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.status === 1 ? 'success' : 'info'">
            {{ scope.row.status === 1 ? '正常' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="remark" show-overflow-tooltip  align="center"/>
      <el-table-column label="操作" align="center" width="180" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasRole="['super_admin']" size="mini" type="text" icon="el-icon-key" @click="handlePerm(scope.row)">分配权限</el-button>
          <el-button
            v-hasRole="['super_admin']"
            size="mini"
            type="text"
            icon="el-icon-delete"
            :disabled="isBuiltin(scope.row)"
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
    <el-dialog :title="title" :visible.sync="open" width="520px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="角色编码" prop="roleKey">
          <el-input v-model="form.roleKey" placeholder="如 ops / repairer" :disabled="isBuiltin(form)" />
        </el-form-item>
        <el-form-item label="显示顺序" prop="roleSort">
          <el-input-number v-model="form.roleSort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">正常</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 分配权限弹窗 -->
    <el-dialog title="分配权限" :visible.sync="permOpen" width="500px" append-to-body :close-on-click-modal="false">
      <el-tree
        ref="permTree"
        :data="permOptions"
        show-checkbox
        node-key="id"
        :props="{ label: 'label', children: 'children' }"
      />
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitPerm">确 定</el-button>
        <el-button @click="permOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listRole, allRole, addRole, updateRole, delRole, getRolePermIds } from "@/api/system/role"
import { menuTree } from "@/api/system/menu"

const builtinKeys = ["super_admin", "tenant_admin", "ops", "repairer"]

export default {
  name: "Role",
  data() {
    return {
      loading: true,
      showSearch: true,
      roleList: [],
      total: 0,
      title: "",
      open: false,
      permOpen: false,
      currentRoleId: undefined,
      permOptions: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        roleName: undefined,
        roleKey: undefined
      },
      form: {},
      rules: {
        roleName: [{ required: true, message: "角色名称不能为空", trigger: "blur" }],
        roleKey: [{ required: true, message: "角色编码不能为空", trigger: "blur" }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listRole(this.queryParams).then(response => {
        this.roleList = response.rows
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
    isBuiltin(row) {
      return row && row.roleKey && builtinKeys.includes(row.roleKey)
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "新增角色"
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row }
      this.open = true
      this.title = "修改角色"
    },
    handlePerm(row) {
      this.currentRoleId = row.id
      this.permOpen = true
      // 加载权限树
      menuTree().then(tree => {
        this.permOptions = tree
        this.$nextTick(() => {
          this.$refs.permTree.setCheckedKeys([])
          // 回显已分配权限
          getRolePermIds(row.id).then(ids => {
            this.$refs.permTree.setCheckedKeys(ids)
          })
        })
      })
    },
    submitPerm() {
      const checkedKeys = this.$refs.permTree.getCheckedKeys()
      const halfCheckedKeys = this.$refs.permTree.getHalfCheckedKeys()
      const permIds = checkedKeys.concat(halfCheckedKeys)
      const role = {
        id: this.currentRoleId,
        permIds: permIds
      }
      // 修改角色时只传权限绑定
      updateRole(role).then(() => {
        this.$modal.msgSuccess("分配权限成功")
        this.permOpen = false
      })
    },
    handleDelete(row) {
      this.$modal.confirm('是否确认删除角色"' + row.roleName + '"？').then(() => {
        return delRole(row.id)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updateRole(this.form).then(() => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addRole(this.form).then(() => {
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
        roleName: undefined,
        roleKey: undefined,
        roleSort: 0,
        status: 1,
        remark: undefined
      }
    },
    cancel() {
      this.open = false
      this.reset()
    }
  }
}
</script>
