<template>
  <div class="app-container">
    <!-- 顶部统计卡片 -->
    <el-row :gutter="12" class="stat-cards">
      <el-col :span="4">
        <div class="stat-card stat-pending">
          <div class="stat-num">{{ stats.pending }}</div>
          <div class="stat-label">待处理</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-dispatched">
          <div class="stat-num">{{ stats.dispatched }}</div>
          <div class="stat-label">已派单</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-repairing">
          <div class="stat-num">{{ stats.repairing }}</div>
          <div class="stat-label">维修中</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-completed">
          <div class="stat-num">{{ stats.completed }}</div>
          <div class="stat-label">已完成</div>
        </div>
      </el-col>
      <el-col :span="4">
        <div class="stat-card stat-total">
          <div class="stat-num">{{ stats.total }}</div>
          <div class="stat-label">工单总数</div>
        </div>
      </el-col>
    </el-row>

    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch" class="search-form">
      <el-form-item label="工单编号" prop="orderNo">
        <el-input v-model="queryParams.orderNo" placeholder="工单编号" clearable size="small" style="width: 170px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="设备名称" prop="deviceName">
        <el-input v-model="queryParams.deviceName" placeholder="设备名称" clearable size="small" style="width: 150px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="故障类型" prop="faultType">
        <el-select v-model="queryParams.faultType" placeholder="故障类型" clearable size="small" style="width: 130px">
          <el-option label="离线" value="offline" />
          <el-option label="链路异常" value="line_abnormal" />
          <el-option label="手动" value="manual" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="工单状态" clearable size="small" style="width: 130px">
          <el-option v-for="(txt, val) in statusMap" :key="val" :label="txt" :value="Number(val)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 操作按钮 -->
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button v-hasPermi="['workorder:order:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增工单</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <!-- 工单列表 -->
    <el-table v-loading="loading" :data="orderList">
      <el-table-column label="工单编号" prop="orderNo" width="200" show-overflow-tooltip />
      <el-table-column label="来源" width="80" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.sourceType === 'event' ? 'danger' : 'info'">
            {{ scope.row.sourceType === 'event' ? '告警' : '手动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="设备" min-width="170" show-overflow-tooltip>
        <template slot-scope="scope">
          <div>{{ scope.row.deviceName }}</div>
          <div class="sub-text">{{ scope.row.deviceIp }}</div>
        </template>
      </el-table-column>
      <el-table-column label="故障类型" width="100" align="center">
        <template slot-scope="scope">{{ faultTypeText(scope.row.faultType) }}</template>
      </el-table-column>
      <el-table-column label="级别" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.severity === 'critical' ? 'danger' : (scope.row.severity === 'warning' ? 'warning' : 'info')">
            {{ scope.row.severity === 'critical' ? '高危' : (scope.row.severity === 'warning' ? '一般' : '信息') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusType(scope.row.status)">{{ scope.row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="维修人员" prop="repairerName" width="100" align="center">
        <template slot-scope="scope">{{ scope.row.repairerName || '-' }}</template>
      </el-table-column>
      <el-table-column label="记录/备件" width="90" align="center">
        <template slot-scope="scope">
          <span>{{ scope.row.recordCount || 0 }}/{{ scope.row.partCount || 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="160" align="center" />
      <el-table-column label="操作" width="230" align="center" fixed="right">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-view" @click="handleDetail(scope.row)">详情</el-button>
          <el-button v-hasPermi="['workorder:order:dispatch']" v-if="scope.row.status === 0" size="mini" type="text" icon="el-icon-s-promotion" style="color:#e6a23c" @click="handleDispatch(scope.row)">报修派单</el-button>
          <el-button v-hasPermi="['workorder:order:complete']" v-if="scope.row.status === 1" size="mini" type="text" icon="el-icon-video-play" @click="handleRepairStart(scope.row)">开始维修</el-button>
          <el-button v-hasPermi="['workorder:order:complete']" v-if="scope.row.status === 1 || scope.row.status === 2" size="mini" type="text" icon="el-icon-circle-check" style="color:#67c23a" @click="handleComplete(scope.row)">完工</el-button>
          <el-button v-hasPermi="['workorder:order:complete']" v-if="scope.row.status === 0 || scope.row.status === 5" size="mini" type="text" icon="el-icon-circle-close" @click="handleClose(scope.row)">关闭</el-button>
          <el-button v-hasPermi="['workorder:order:edit']" v-if="scope.row.status === 0" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['workorder:order:remove']" v-if="scope.row.status === 0" size="mini" type="text" icon="el-icon-delete" style="color:#f56c6c" @click="handleDelete(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改工单弹窗 -->
    <el-dialog :title="form.id ? '修改工单' : '新增工单'" :visible.sync="open" width="620px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-divider content-position="left">设备信息</el-divider>
        <el-row>
          <el-col :span="12">
            <el-form-item label="设备名称" prop="deviceName">
              <el-input v-model="form.deviceName" placeholder="设备名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备IP" prop="deviceIp">
              <el-input v-model="form.deviceIp" placeholder="管理IP" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="设备类型">
              <el-select v-model="form.deviceType" placeholder="设备类型" style="width:100%">
                <el-option label="网络设备" value="network" />
                <el-option label="摄像头" value="camera" />
                <el-option label="NVR" value="nvr" />
                <el-option label="门禁控制器" value="door_controller" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="部署位置">
              <el-input v-model="form.deviceLocation" placeholder="楼宇/机房/点位" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider content-position="left">故障信息</el-divider>
        <el-row>
          <el-col :span="12">
            <el-form-item label="故障类型">
              <el-select v-model="form.faultType" placeholder="故障类型" style="width:100%">
                <el-option label="手动报修" value="manual" />
                <el-option label="设备离线" value="offline" />
                <el-option label="链路异常" value="line_abnormal" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="级别">
              <el-select v-model="form.severity" placeholder="级别" style="width:100%">
                <el-option label="信息" value="info" />
                <el-option label="一般" value="warning" />
                <el-option label="高危" value="critical" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="故障描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="故障现象描述" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 报修派单弹窗 -->
    <el-dialog title="报修派单" :visible.sync="dispatchOpen" width="520px" append-to-body :close-on-click-modal="false">
      <el-alert title="派单后将自动通过短信与微信公众号通知维修人员" type="warning" :closable="false" show-icon style="margin-bottom:16px" />
      <el-form ref="dispatchForm" :model="dispatchForm" :rules="dispatchRules" label-width="90px">
        <el-form-item label="工单编号">
          <el-input :value="currentOrder && currentOrder.orderNo" disabled />
        </el-form-item>
        <el-form-item label="故障设备">
          <el-input :value="(currentOrder && currentOrder.deviceName) + ' (' + (currentOrder && currentOrder.deviceIp) + ')'" disabled />
        </el-form-item>
        <el-form-item label="维修人员" prop="repairerId">
          <el-select v-model="dispatchForm.repairerId" placeholder="选择维修人员（自动推荐优先）" style="width:100%">
            <el-option v-for="r in repairerOptions" :key="r.id" :value="r.id"
              :label="r.name + '（' + (r.region || '未分区') + '）'">
              <span>{{ r.name }}</span>
              <span style="float:right;color:#8492a6;font-size:12px">{{ r.region || '未分区' }} · {{ r.skills || '无技能标签' }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input v-model="dispatchForm.remark" type="textarea" :rows="3" placeholder="上门时间、注意事项等" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="dispatchLoading" @click="submitDispatch">确认派单并通知</el-button>
        <el-button @click="dispatchOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 完工弹窗 -->
    <el-dialog title="完工确认" :visible.sync="completeOpen" width="480px" append-to-body :close-on-click-modal="false">
      <el-form ref="completeForm" :model="completeForm" :rules="completeRules" label-width="90px">
        <el-form-item label="工单编号">
          <el-input :value="currentOrder && currentOrder.orderNo" disabled />
        </el-form-item>
        <el-form-item label="维修结果" prop="repairResult">
          <el-input v-model="completeForm.repairResult" type="textarea" :rows="3" placeholder="维修处理过程与结果，如：更换光纤模块后恢复" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitComplete">确认完工</el-button>
        <el-button @click="completeOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 关闭工单弹窗 -->
    <el-dialog title="关闭工单" :visible.sync="closeOpen" width="480px" append-to-body :close-on-click-modal="false">
      <el-form ref="closeForm" :model="closeForm" label-width="90px">
        <el-form-item label="工单编号">
          <el-input :value="currentOrder && currentOrder.orderNo" disabled />
        </el-form-item>
        <el-form-item label="关闭原因">
          <el-input v-model="closeForm.remark" type="textarea" :rows="3" placeholder="如：远程复位后恢复 / 设备已自动恢复，无需维修" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitClose">确认关闭</el-button>
        <el-button @click="closeOpen = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 工单详情弹窗 -->
    <el-dialog title="工单详情" :visible.sync="detailOpen" width="720px" append-to-body :close-on-click-modal="false">
      <template v-if="detail.order">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="工单编号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="工单状态">
            <el-tag size="mini" :type="statusType(detail.order.status)">{{ detail.order.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="设备名称">{{ detail.order.deviceName }}</el-descriptions-item>
          <el-descriptions-item label="设备IP">{{ detail.order.deviceIp }}</el-descriptions-item>
          <el-descriptions-item label="设备类型">{{ deviceTypeText(detail.order.deviceType) }}</el-descriptions-item>
          <el-descriptions-item label="部署位置">{{ detail.order.deviceLocation || '-' }}</el-descriptions-item>
          <el-descriptions-item label="故障类型">{{ faultTypeText(detail.order.faultType) }}</el-descriptions-item>
          <el-descriptions-item label="告警级别">
            <el-tag size="mini" :type="detail.order.severity === 'critical' ? 'danger' : (detail.order.severity === 'warning' ? 'warning' : 'info')">
              {{ detail.order.severity === 'critical' ? '高危' : (detail.order.severity === 'warning' ? '一般' : '信息') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ detail.order.sourceType === 'event' ? '告警事件自动生成' : '手动创建' }}</el-descriptions-item>
          <el-descriptions-item label="维修人员">{{ detail.order.repairerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="派单时间">{{ detail.order.dispatchTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="维修开始">{{ detail.order.repairStartTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="维修完成">{{ detail.order.repairEndTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="故障描述" :span="2">{{ detail.order.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="维修结果" :span="2">{{ detail.order.repairResult || '-' }}</el-descriptions-item>
          <el-descriptions-item label="补充说明" :span="2">{{ detail.order.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">处理记录</el-divider>
        <el-timeline v-if="detail.records && detail.records.length">
          <el-timeline-item v-for="rec in detail.records" :key="rec.id" :timestamp="rec.createTime" :color="timelineColor(rec.action)">
            <b>{{ actionText(rec.action) }}</b>（{{ rec.operator }}）
            <div class="sub-text">{{ rec.content }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无处理记录" :image-size="60" />

        <el-divider content-position="left">备件使用（{{ detail.parts ? detail.parts.length : 0 }}）</el-divider>
        <el-table v-if="detail.parts && detail.parts.length" :data="detail.parts" size="mini" border>
          <el-table-column label="备件编号" prop="partNo" />
          <el-table-column label="备件名称" prop="partName" />
          <el-table-column label="领用数量" prop="quantity" width="80" align="center" />
          <el-table-column label="单位" prop="unit" width="60" align="center" />
        </el-table>
        <el-empty v-else description="未领用备件" :image-size="60" />

        <el-divider content-position="left">相关故障知识（{{ detail.knowledge ? detail.knowledge.length : 0 }}）</el-divider>
        <el-empty v-if="!detail.knowledge || !detail.knowledge.length" description="暂无匹配的故障知识" :image-size="60" />
        <div v-for="k in detail.knowledge" :key="k.id" class="knowledge-item" @click="openKnowledge(k)">
          <span class="knowledge-title">{{ k.title }}</span>
          <el-tag size="mini" effect="plain">{{ k.category }}</el-tag>
          <el-tag size="mini" :type="k.severityRef === 'critical' ? 'danger' : (k.severityRef === 'warning' ? 'warning' : 'info')" effect="plain">{{ k.severityRef === 'critical' ? '高' : (k.severityRef === 'warning' ? '中' : '低') }}</el-tag>
          <span class="knowledge-views"><i class="el-icon-view" /> {{ k.viewCount }}</span>
        </div>
        <el-dialog title="故障知识详情" :visible.sync="knowledgeVisible" width="620px" append-to-body :close-on-click-modal="false">
          <template v-if="currentKnowledge">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="标题" :span="2">{{ currentKnowledge.title }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ currentKnowledge.category }}</el-descriptions-item>
              <el-descriptions-item label="设备类型">{{ deviceTypeText(currentKnowledge.deviceType) }}</el-descriptions-item>
            </el-descriptions>
            <el-divider content-position="left">故障现象</el-divider>
            <p class="sub-text" style="white-space:pre-line;margin:4px 0 8px">{{ currentKnowledge.symptom || '无' }}</p>
            <el-divider content-position="left">可能原因</el-divider>
            <p class="sub-text" style="white-space:pre-line;margin:4px 0 8px">{{ currentKnowledge.cause || '无' }}</p>
            <el-divider content-position="left">处理步骤</el-divider>
            <p class="sub-text" style="white-space:pre-line;margin:4px 0 8px">{{ currentKnowledge.solution || '无' }}</p>
          </template>
        </el-dialog>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { listOrder, getOrderStats, getOrder, addOrder, updateOrder, delOrder, dispatchOrder, repairStartOrder, completeOrder, closeOrder } from '@/api/workorder/order'
import { recommendFault } from '@/api/knowledge/fault'
import { listRepairerOptions } from '@/api/workorder/repairer'
import Pagination from '@/components/Pagination'

export default {
  name: 'WorkOrder',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      orderList: [],
      total: 0,
      stats: { pending: 0, dispatched: 0, repairing: 0, completed: 0, total: 0 },
      statusMap: { 0: '待处理', 1: '已派单', 2: '维修中', 3: '已完成', 4: '已关闭', 5: '已自动恢复' },
      queryParams: { pageNum: 1, pageSize: 10, orderNo: undefined, deviceName: undefined, faultType: undefined, status: undefined },
      open: false,
      form: {},
      rules: {
        deviceName: [{ required: true, message: '设备名称不能为空', trigger: 'blur' }],
        deviceIp: [{ required: true, message: '设备IP不能为空', trigger: 'blur' }]
      },
      dispatchOpen: false,
      dispatchLoading: false,
      dispatchForm: { repairerId: undefined, remark: '' },
      dispatchRules: { repairerId: [{ required: true, message: '请选择维修人员', trigger: 'change' }] },
      repairerOptions: [],
      completeOpen: false,
      completeForm: { repairResult: '' },
      completeRules: { repairResult: [{ required: true, message: '请填写维修结果', trigger: 'blur' }] },
      closeOpen: false,
      closeForm: { remark: '' },
      detailOpen: false,
      detail: {},
      knowledgeVisible: false,
      currentKnowledge: null,
      currentOrder: null
    }
  },
  created() {
    this.getList()
    this.getStats()
  },
  methods: {
    getList() {
      this.loading = true
      listOrder(this.queryParams).then(response => {
        this.orderList = response.rows
        this.total = response.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    getStats() {
      getOrderStats().then(data => { this.stats = data })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleAdd() {
      this.reset()
      this.form = { faultType: 'manual', severity: 'info', deviceType: 'network' }
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
          updateOrder(this.form).then(() => {
            this.$modal.msgSuccess('修改成功')
            this.open = false
            this.getList()
          })
        } else {
          addOrder(this.form).then(() => {
            this.$modal.msgSuccess('联系单已生成')
            this.open = false
            this.getList()
            this.getStats()
          })
        }
      })
    },
    cancel() { this.open = false; this.reset() },
    reset() { this.form = {} },
    handleDispatch(row) {
      this.currentOrder = row
      this.dispatchForm = { repairerId: undefined, remark: '' }
      listRepairerOptions().then(data => { this.repairerOptions = data })
      this.dispatchOpen = true
    },
    submitDispatch() {
      this.$refs['dispatchForm'].validate(valid => {
        if (!valid) return
        this.dispatchLoading = true
        dispatchOrder({ id: this.currentOrder.id, repairerId: this.dispatchForm.repairerId, remark: this.dispatchForm.remark }).then(() => {
          this.$modal.msgSuccess('已报修派单并通知维修人员')
          this.dispatchOpen = false
          this.dispatchLoading = false
          this.getList()
          this.getStats()
        }).catch(() => { this.dispatchLoading = false })
      })
    },
    handleRepairStart(row) {
      this.$confirm('确认开始维修？工单将进入「维修中」状态', '提示', { type: 'warning' }).then(() => {
        repairStartOrder(row.id).then(() => {
          this.$modal.msgSuccess('已开始维修')
          this.getList()
          this.getStats()
        })
      })
    },
    handleComplete(row) {
      this.currentOrder = row
      this.completeForm = { repairResult: '' }
      this.completeOpen = true
    },
    submitComplete() {
      this.$refs['completeForm'].validate(valid => {
        if (!valid) return
        completeOrder({ id: this.currentOrder.id, repairResult: this.completeForm.repairResult }).then(() => {
          this.$modal.msgSuccess('工单已完成')
          this.completeOpen = false
          this.getList()
          this.getStats()
        })
      })
    },
    handleClose(row) {
      this.currentOrder = row
      this.closeForm = { remark: '' }
      this.closeOpen = true
    },
    submitClose() {
      closeOrder({ id: this.currentOrder.id, remark: this.closeForm.remark }).then(() => {
        this.$modal.msgSuccess('工单已关闭')
        this.closeOpen = false
        this.getList()
        this.getStats()
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除工单 ' + row.orderNo + '？', '警告', { type: 'warning' }).then(() => {
        delOrder(row.id).then(() => {
          this.$modal.msgSuccess('删除成功')
          this.getList()
          this.getStats()
        })
      })
    },
    handleDetail(row) {
      this.detailOpen = true
      this.detail = {}
      getOrder(row.id).then(data => {
        this.detail = data
        this.loadKnowledge(row)
      })
    },
    loadKnowledge(row) {
      // 故障知识联动推荐（设备类型 + 故障类型）；$set 保证 Vue2 响应式更新
      recommendFault({ deviceType: row.deviceType, faultType: row.faultType }).then(knowledge => {
        this.$set(this.detail, 'knowledge', knowledge || [])
      })
    },
    openKnowledge(k) {
      this.currentKnowledge = k
      this.knowledgeVisible = true
    },
    faultTypeText(t) {
      return { offline: '设备离线', line_abnormal: '链路异常', manual: '手动报修' }[t] || t
    },
    deviceTypeText(t) {
      return { network: '网络设备', camera: '摄像头', nvr: 'NVR', door_controller: '门禁控制器' }[t] || t
    },
    statusType(s) {
      return { 0: 'warning', 1: 'primary', 2: 'danger', 3: 'success', 4: 'info', 5: 'success' }[s] || 'info'
    },
    actionText(a) {
      return { auto_create: '联系单生成', dispatch: '报修派单', repair_start: '开始维修', complete: '完工', close: '关闭', recover: '自动恢复' }[a] || a
    },
    timelineColor(a) {
      return { auto_create: '#909399', dispatch: '#e6a23c', repair_start: '#409eff', complete: '#67c23a', close: '#909399', recover: '#67c23a' }[a] || '#409eff'
    }
  }
}
</script>

<style scoped>
.stat-cards { margin-bottom: 16px; }
.stat-card {
  border-radius: 6px;
  padding: 16px 12px;
  text-align: center;
  color: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.1);
}
.stat-num { font-size: 26px; font-weight: 700; line-height: 1.2; }
.stat-label { font-size: 13px; opacity: 0.9; margin-top: 2px; }
.stat-pending { background: linear-gradient(135deg, #f6ad55, #ed8936); }
.stat-dispatched { background: linear-gradient(135deg, #63b3ed, #3182ce); }
.stat-repairing { background: linear-gradient(135deg, #b794f4, #805ad5); }
.stat-completed { background: linear-gradient(135deg, #68d391, #38a169); }
.stat-total { background: linear-gradient(135deg, #a0aec0, #718096); }
.search-form { margin-bottom: 4px; }
.mb8 { margin-bottom: 10px; }
.sub-text { color: #909399; font-size: 12px; }
.knowledge-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; margin-bottom: 6px;
  background: #F8FAFC; border: 1px solid #EEF2F7; border-radius: 6px; cursor: pointer;
  transition: border-color .2s, background .2s;
}
.knowledge-item:hover { border-color: #205CF5; background: #EAF0FB; }
.knowledge-title { flex: 1; font-size: 13px; color: #1F2937; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-views { font-size: 12px; color: #6B7280; }
</style>
