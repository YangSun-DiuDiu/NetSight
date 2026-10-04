<template>
  <div class="app-container">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-box" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.totalParts }}</div>
            <div class="stat-label">备件种类</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-goods" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.totalStock }}</div>
            <div class="stat-label">库存总量</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-refresh-right" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.repairing }}</div>
            <div class="stat-label">返修中</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FEE2E2;color:#EF4444">
            <i class="el-icon-warning-outline" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.lowStock }}</div>
            <div class="stat-label">低于安全库存</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="关键字" prop="keyword">
        <el-input v-model="queryParams.keyword" placeholder="编号/名称/型号/序列号" clearable size="small" style="width: 180px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="类型" prop="partType">
        <el-select v-model="queryParams.partType" placeholder="备件类型" clearable size="small" style="width: 140px">
          <el-option v-for="t in partTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="备件状态" clearable size="small" style="width: 120px">
          <el-option label="全新" value="new" />
          <el-option label="返修中" value="repairing" />
          <el-option label="已修复" value="repaired" />
          <el-option label="已报废" value="scrapped" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['spare:part:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增备件</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table border v-loading="loading" :data="partList" :row-class-name="rowClassName">
      <el-table-column label="备件编号" prop="partNo" width="240" show-overflow-tooltip  align="center"/>
      <el-table-column label="类型" prop="partType" width="120"  align="center"/>
      <el-table-column label="品牌" prop="brand" width="90"  align="center"/>
      <el-table-column label="型号" prop="model" width="70" show-overflow-tooltip  align="center"/>
      <el-table-column label="序列号" prop="serialNo" min-width="140" show-overflow-tooltip  align="center"/>
      <el-table-column label="库存" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.quantity <= scope.row.safeStock ? 'danger' : 'success'">
            {{ scope.row.quantity }} {{ scope.row.unit }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="存放位置" prop="location" min-width="120" show-overflow-tooltip  align="center"/>
      <el-table-column label="安全库存" prop="safeStock" width="90" align="center" />
      <el-table-column label="操作" align="center" width="180" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button v-hasPermi="['spare:part:stock']" size="mini" type="text" icon="el-icon-sold-out"  @click="handleStock(scope.row)">出入库</el-button>
          <el-button v-hasPermi="['spare:part:edit']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['spare:part:remove']" size="mini" type="text" icon="el-icon-delete" style="color:#f56c6c" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改备件弹窗 -->
    <el-dialog :title="form.id ? '修改备件' : '新增备件'" :visible.sync="open" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-row>
          <el-col :span="12">
            <el-form-item label="备件类型" prop="partType">
              <el-select v-model="form.partType" filterable allow-create placeholder="选择或输入类型" style="width:100%">
                <el-option v-for="t in partTypes" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="品牌">
              <el-input v-model="form.brand" placeholder="品牌" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="型号" prop="model">
              <el-input v-model="form.model" placeholder="型号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="序列号">
              <el-input v-model="form.serialNo" placeholder="序列号/批号" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="8">
            <el-form-item label="数量" prop="quantity">
              <el-input-number v-model="form.quantity" :min="0" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位">
              <el-select v-model="form.unit" allow-create filterable style="width:100%">
                <el-option label="台" value="台" />
                <el-option label="个" value="个" />
                <el-option label="块" value="块" />
                <el-option label="根" value="根" />
                <el-option label="箱" value="箱" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="安全库存">
              <el-input-number v-model="form.safeStock" :min="0" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width:100%">
                <el-option label="全新" value="new" />
                <el-option label="返修中" value="repairing" />
                <el-option label="已修复" value="repaired" />
                <el-option label="已报废" value="scrapped" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="存放位置">
              <el-input v-model="form.location" placeholder="如：机房备件柜A-3" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 出入库弹窗 -->
    <el-dialog title="备件出入库" :visible.sync="stockOpen" width="560px" append-to-body :close-on-click-modal="false">
      <el-alert :title="stockAlert" type="info" :closable="false" show-icon style="margin-bottom:16px" />
      <el-form ref="stockForm" :model="stockForm" :rules="stockRules" label-width="100px">
        <el-form-item label="备件">
          <el-input :value="currentPart.partNo + '（' + currentPart.partType + ' ' + currentPart.model + '）库存 ' + currentPart.quantity + ' ' + currentPart.unit" disabled />
        </el-form-item>
        <el-form-item label="操作类型" prop="recordType">
          <el-radio-group v-model="stockForm.recordType">
            <el-radio label="in">入库</el-radio>
            <el-radio label="out">领用出库</el-radio>
            <el-radio label="repair">返修</el-radio>
            <el-radio label="return_in">返修入库</el-radio>
            <el-radio label="scrap">报废</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number v-model="stockForm.quantity" :min="1" :max="stockForm.recordType === 'out' || stockForm.recordType === 'repair' || stockForm.recordType === 'scrap' ? currentPart.quantity : 9999" style="width:100%" />
        </el-form-item>
        <el-form-item v-if="stockForm.recordType === 'out'" label="关联工单" prop="orderId">
          <el-select v-model="stockForm.orderId" filterable placeholder="选择使用备件的工单" style="width:100%">
            <el-option v-for="o in orderOptions" :key="o.id" :value="o.id" :label="o.orderNo + '（' + o.deviceName + ' · ' + statusMap[o.status] + '）'" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="stockForm.remark" type="textarea" :rows="2" :placeholder="stockForm.recordType === 'out' ? '用途说明' : ''" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitStock">确 定</el-button>
        <el-button @click="stockOpen = false">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listPart, getPartStats, listPartTypes, listAvailableParts, addPart, updatePart, delPart, stockPart } from '@/api/spare/part'
import { listOrder } from '@/api/workorder/order'
import Pagination from '@/components/Pagination'

export default {
  name: 'SparePart',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      partList: [],
      partTypes: [],
      total: 0,
      stats: { totalParts: 0, totalStock: 0, repairing: 0, lowStock: 0 },
      statusMap: { 0: '待处理', 1: '已派单', 2: '维修中', 3: '已完成', 4: '已关闭', 5: '已自动恢复' },
      queryParams: { pageNum: 1, pageSize: 10, keyword: undefined, partType: undefined, status: undefined },
      open: false,
      form: {},
      rules: {
        partType: [{ required: true, message: '备件类型不能为空', trigger: 'change' }],
        model: [{ required: true, message: '型号不能为空', trigger: 'blur' }],
        quantity: [{ required: true, message: '数量不能为空', trigger: 'blur' }]
      },
      stockOpen: false,
      stockForm: { recordType: 'out', quantity: 1, orderId: undefined, remark: '' },
      stockRules: {
        quantity: [{ required: true, message: '数量不能为空', trigger: 'blur' }],
        orderId: [{ required: true, message: '领用出库必须关联工单', trigger: 'change' }]
      },
      currentPart: {},
      orderOptions: []
    }
  },
  computed: {
    stockAlert() {
      const t = {
        in: '采购/退库入库，增加库存',
        out: '领用出库并绑定工单，库存扣减',
        repair: '换下的旧件送返修，状态变为返修中',
        return_in: '返修完成回库，状态变为已修复',
        scrap: '报废处理，从库存中核减'
      }
      return t[this.stockForm.recordType] || ''
    }
  },
  created() {
    this.getList()
    this.getStats()
    this.getPartTypes()
  },
  methods: {
    getList() {
      this.loading = true
      listPart(this.queryParams).then(response => {
        this.partList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    getStats() {
      getPartStats().then(data => { this.stats = data })
    },
    getPartTypes() {
      listPartTypes().then(data => { this.partTypes = data })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleAdd() {
      this.reset()
      this.form = { quantity: 1, unit: '台', safeStock: 0, status: 'new' }
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = { ...row }
      this.open = true
    },
    submitForm() {
      this.$refs['form'].validate(valid => {
        if (!valid) return
        if (this.form.id) {
          updatePart(this.form).then(() => {
            this.$modal.msgSuccess('修改成功')
            this.open = false
            this.getList()
          })
        } else {
          addPart(this.form).then(() => {
            this.$modal.msgSuccess('备件已登记')
            this.open = false
            this.getList()
            this.getStats()
          })
        }
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除备件 ' + row.partNo + '？', '警告', { type: 'warning' }).then(() => {
        delPart(row.id).then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
          this.getStats()
        })
      })
    },
    handleStock(row) {
      this.currentPart = row
      this.stockForm = { recordType: 'out', quantity: 1, orderId: undefined, remark: '' }
      this.orderOptions = []
      listOrder({ pageNum: 1, pageSize: 50, status: undefined }).then(data => {
        this.orderOptions = data.rows.filter(o => [0, 1, 2, 3].indexOf(o.status) > -1)
      })
      this.stockOpen = true
    },
    submitStock() {
      this.$refs['stockForm'].validate(valid => {
        if (!valid) return
        stockPart({
          partId: this.currentPart.id,
          recordType: this.stockForm.recordType,
          quantity: this.stockForm.quantity,
          orderId: this.stockForm.orderId,
          remark: this.stockForm.remark
        }).then(() => {
          this.$modal.msgSuccess('出入库操作完成')
          this.stockOpen = false
          this.getList()
          this.getStats()
        })
      })
    },
    rowClassName({ row }) {
      return row.quantity <= row.safeStock ? 'row-low-stock' : ''
    },
    statusType(s) {
      return { new: 'success', repairing: 'warning', repaired: 'primary', scrapped: 'info' }[s] || 'info'
    },
    statusText(s) {
      return { new: '全新', repairing: '返修中', repaired: '已修复', scrapped: '已报废' }[s] || s
    },
    cancel() { this.open = false; this.reset() },
    reset() { this.form = {} }
  }
}
</script>

<style scoped>
.search-form { margin-bottom: 4px; }
.mb8 { margin-bottom: 10px; }
</style>

<style>
.el-table .row-low-stock { background: #fef0f0; }
</style>
