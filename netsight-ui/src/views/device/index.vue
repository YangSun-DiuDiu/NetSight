<template>
  <div class="app-container">
    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="设备名称" prop="deviceName">
        <el-input
          v-model="queryParams.deviceName"
          placeholder="请输入设备名称"
          clearable
          size="small"
          style="width: 180px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="设备类型" prop="deviceType">
        <el-select v-model="queryParams.deviceType" placeholder="设备类型" clearable size="small" style="width: 140px">
          <el-option v-for="dict in typeOptions" :key="dict.value" :label="dict.label" :value="dict.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="运行状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="运行状态" clearable size="small" style="width: 130px">
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
        <el-button v-hasRole="['super_admin','tenant_admin']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增设备</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button v-hasRole="['super_admin','tenant_admin','ops']" type="info" plain icon="el-icon-share" size="mini" @click="handleTopology">拓扑维护</el-button>
      </el-col>
    </el-row>

    <!-- 设备列表 -->
    <el-table v-loading="loading" :data="deviceList">
      <el-table-column label="状态" width="70" align="center">
        <template slot-scope="scope">
          <el-tooltip :content="statusTooltip(scope.row)" placement="top">
            <span class="status-dot" :style="{ background: statusColor(scope.row) }"></span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="设备名称" prop="deviceName" min-width="130" />
      <el-table-column label="设备编码" prop="deviceCode" width="170" />
      <el-table-column label="设备类型" width="110" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="typeTag(scope.row.deviceType)">{{ typeText(scope.row.deviceType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="品牌/型号" min-width="140">
        <template slot-scope="scope">{{ scope.row.brand }} {{ scope.row.model }}</template>
      </el-table-column>
      <el-table-column label="管理IP" prop="ipAddress" width="130" />
      <el-table-column label="部署位置" prop="location" min-width="110" show-overflow-tooltip />
      <el-table-column label="保修期" width="150" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.warrantyExpire" :style="{ marginRight: '4px' }">{{ scope.row.warrantyExpire }}</span>
          <el-tag size="mini" :type="warrantyTag(scope.row.warrantyStatus)">{{ warrantyText(scope.row.warrantyStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="归属网关" prop="gatewayName" min-width="110" />
      <el-table-column label="上级设备" min-width="140">
        <template slot-scope="scope">
          <span v-if="!scope.row.parentNames || scope.row.parentNames.length === 0" style="color: #bbb">未配置</span>
          <el-tag v-for="(p, i) in scope.row.parentNames" :key="i" size="mini" style="margin-right: 4px" :type="i === 0 ? 'primary' : 'warning'">{{ p }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="230" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin','tenant_admin','ops']" size="mini" type="text" icon="el-icon-camera" @click="handleQrcode(scope.row)">二维码</el-button>
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasRole="['super_admin','tenant_admin']" size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
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

    <!-- 新增/修改弹窗（含拓扑主备三路）：点击弹窗外部不关闭 -->
    <el-dialog :title="title" :visible.sync="open" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="设备名称" prop="deviceName">
          <el-input v-model="form.deviceName" placeholder="请输入设备名称" />
        </el-form-item>
        <el-form-item label="设备类型" prop="deviceType">
          <el-select v-model="form.deviceType" placeholder="选择设备类型" style="width: 100%">
            <el-option v-for="dict in typeOptions" :key="dict.value" :label="dict.label" :value="dict.value" />
          </el-select>
        </el-form-item>
        <el-row>
          <el-col :span="12">
            <el-form-item label="品牌" prop="brand">
              <el-input v-model="form.brand" placeholder="品牌" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="型号" prop="model">
              <el-input v-model="form.model" placeholder="型号" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="管理IP" prop="ipAddress">
              <el-input v-model="form.ipAddress" placeholder="管理IP" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="部署位置" prop="location">
              <el-input v-model="form.location" placeholder="如：一号厂房" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="保修截止" prop="warrantyExpire">
              <el-date-picker
                v-model="form.warrantyExpire"
                type="date"
                placeholder="选择保修截止日期"
                value-format="yyyy-MM-dd"
                clearable
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="归属网关" prop="gatewayId">
          <el-select v-model="form.gatewayId" placeholder="选择网关" clearable style="width: 100%">
            <el-option v-for="item in gatewayOptions" :key="item.id" :label="item.gatewayName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属租户" prop="tenantId" v-if="isSuperAdmin">
          <el-select v-model="form.tenantId" placeholder="选择租户" style="width: 100%">
            <el-option v-for="item in tenantOptions" :key="item.id" :label="item.tenantName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-divider content-position="left">拓扑关系（1 主 + 2 备 上级，可维护）</el-divider>
        <el-form-item label="主上级" prop="parentMainId">
          <el-select v-model="form.parentMainId" placeholder="选择主上级设备" clearable filterable style="width: 100%">
            <el-option v-for="item in parentOptions" :key="item.id" :label="item.name + ' (' + typeText(item.deviceType) + ')'" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-row>
          <el-col :span="12">
            <el-form-item label="备路1上级" prop="parentBackup1Id">
              <el-select v-model="form.parentBackup1Id" placeholder="选择备路1上级" clearable filterable style="width: 100%">
                <el-option v-for="item in parentOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="备路2上级" prop="parentBackup2Id">
              <el-select v-model="form.parentBackup2Id" placeholder="选择备路2上级" clearable filterable style="width: 100%">
                <el-option v-for="item in parentOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 拓扑维护预览 -->
    <el-dialog title="网络拓扑（主备上级关系）" :visible.sync="topoOpen" width="820px" append-to-body :close-on-click-modal="false">
      <div class="topo-wrap" ref="topoWrap">
        <div v-if="topoLoading" class="topo-empty">拓扑加载中...</div>
        <div v-else-if="!topoData.nodes || topoData.nodes.length === 0" class="topo-empty">暂无设备，请先在设备列表新增设备</div>
        <svg v-else :viewBox="'0 0 ' + svgW + ' ' + svgH" class="topo-preview-svg" preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">
          <!-- 连线（主备三路） -->
          <line v-for="(l, i) in topoData.links" :key="'l' + i"
            :x1="nodeX(l.from)" :y1="nodeY(l.from)" :x2="nodeX(l.to)" :y2="nodeY(l.to)"
            :stroke="linkColor(l.lineType)" :stroke-width="l.lineType === 'main' ? 2.5 : 1.5"
            :stroke-dasharray="l.lineType === 'main' ? 'none' : '6 4'" />
          <!-- 设备节点 -->
          <g v-for="(n, i) in topoData.nodes" :key="'n' + i" :transform="'translate(' + nodeX(n.id) + ',' + nodeY(n.id) + ')'">
            <circle r="16" :fill="statusColorOf(n)" stroke="#fff" stroke-width="2" />
            <text y="-24" text-anchor="middle" font-size="12" fill="#333">{{ n.name }}</text>
          </g>
        </svg>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="topoOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <!-- 设备二维码弹窗（V1.2.7 demo 5/5）：点击弹窗外部不关闭 -->
    <el-dialog title="设备二维码" :visible.sync="qrOpen" width="460px" append-to-body :close-on-click-modal="false">
      <div v-if="qrLoading" class="qr-empty">二维码生成中...</div>
      <div v-else class="qr-body">
        <div class="qr-left">
          <div id="qrContainer" class="qr-canvas"></div>
          <div class="qr-tip">扫码识别设备 · 贴于设备机柜/外壳</div>
        </div>
        <div class="qr-right">
          <div class="qr-info">
            <div class="qr-row"><span class="qr-label">设备名称</span>{{ qrDevice.deviceName }}</div>
            <div class="qr-row"><span class="qr-label">设备编码</span>{{ qrDevice.deviceCode }}</div>
            <div class="qr-row"><span class="qr-label">设备类型</span>{{ qrTypeText }}</div>
            <div class="qr-row"><span class="qr-label">管理IP</span>{{ qrDevice.ipAddress || '-' }}</div>
            <div class="qr-row"><span class="qr-label">部署位置</span>{{ qrDevice.location || '-' }}</div>
          </div>
        </div>
      </div>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" icon="el-icon-download" @click="downloadQrcode">下载标签</el-button>
        <el-button @click="qrOpen = false">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listDevice, addDevice, updateDevice, delDevice, getTopologyTree, getQrcodeInfo } from '@/api/device/device'
import { listGatewayOptions } from '@/api/edge/gateway'
import { listTenant } from '@/api/system/tenant'
import QRCode from 'qrcodejs2'

export default {
  name: 'Device',
  data() {
    return {
      loading: false,
      showSearch: true,
      deviceList: [],
      allDevices: [],
      gatewayOptions: [],
      tenantOptions: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, deviceName: undefined, deviceType: undefined, status: undefined },
      title: '',
      open: false,
      topoOpen: false,
      topoLoading: false,
      topoData: { nodes: [], links: [] },
      qrOpen: false,
      qrLoading: false,
      qrDevice: {},
      oldWarranty: undefined,
      typeOptions: [
        { value: 'network', label: '网络设备' },
        { value: 'camera', label: '视频摄像头' },
        { value: 'nvr', label: 'NVR录像机' },
        { value: 'door_controller', label: '门禁控制器' }
      ],
      form: {},
      rules: {
        deviceName: [{ required: true, message: '设备名称不能为空', trigger: 'blur' }],
        deviceType: [{ required: true, message: '请选择设备类型', trigger: 'change' }]
      }
    }
  },
  computed: {
    isSuperAdmin() {
      return this.$store.getters.roles.includes('super_admin')
    },
    // 二维码类型文本（V1.2.7）
    qrTypeText() {
      return this.typeText(this.qrDevice.deviceType)
    },
    // 上级设备候选：本租户全部设备（拓扑树节点），不受列表分页限制
    parentOptions() {
      const curId = this.form.id
      return this.allDevices.filter(d => d.id !== curId)
    },
    svgW() { return 780 },
    svgH() { return 420 }
  },
  created() {
    this.getList()
    this.getGatewayOptions()
    this.loadParentOptions()
    if (this.isSuperAdmin) {
      this.getTenantOptions()
    }
  },
  methods: {
    getList() {
      this.loading = true
      listDevice(this.queryParams).then(res => {
        this.deviceList = res.rows
        this.total = res.total
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    // 加载全量设备（拓扑树节点），作为主备上级下拉候选
    loadParentOptions() {
      getTopologyTree().then(data => {
        this.allDevices = (data && data.nodes) || []
      }).catch(() => { this.allDevices = [] })
    },
    getGatewayOptions() {
      listGatewayOptions().then(res => { this.gatewayOptions = res })
    },
    getTenantOptions() {
      listTenant({ pageNum: 1, pageSize: 100 }).then(res => { this.tenantOptions = res.rows })
    },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleAdd() {
      this.reset()
      this.title = '新增设备'
      this.open = true
    },
    handleUpdate(row) {
      this.reset()
      this.form = {
        id: row.id, deviceCode: row.deviceCode, deviceName: row.deviceName, deviceType: row.deviceType,
        brand: row.brand, model: row.model, ipAddress: row.ipAddress, location: row.location,
        gatewayId: row.gatewayId, tenantId: row.tenantId, status: row.status, lineStatus: row.lineStatus,
        warrantyExpire: row.warrantyExpire || undefined,
        parentMainId: row.parentMainId, parentBackup1Id: row.parentBackup1Id, parentBackup2Id: row.parentBackup2Id
      }
      this.oldWarranty = row.warrantyExpire || undefined
      this.title = '修改设备'
      this.open = true
    },
    handleDelete(row) {
      this.$modal.confirm('确认删除设备【' + row.deviceName + '】？删除后其拓扑关系一并清理。').then(() => {
        delDevice(row.id).then(() => {
          this.getList()
          this.$modal.msgSuccess('删除成功')
        })
      }).catch(() => {})
    },
    handleTopology() {
      this.topoOpen = true
      this.topoLoading = true
      getTopologyTree().then(res => {
        this.topoData = res
        this.topoLoading = false
      }).catch(() => { this.topoLoading = false })
    },
    // ===== 设备二维码（V1.2.7 demo 5/5） =====
    handleQrcode(row) {
      this.qrLoading = true
      this.qrOpen = true
      this.qrDevice = {}
      getQrcodeInfo(row.id).then(info => {
        this.qrDevice = info
        this.qrLoading = false
        // 弹窗 DOM 挂载后生成二维码（容器需清空避免重复实例叠加）
        this.$nextTick(() => {
          const el = document.getElementById('qrContainer')
          if (!el) return
          el.innerHTML = ''
          const text = JSON.stringify({
            t: 'device',
            c: info.deviceCode,
            n: info.deviceName,
            ty: info.deviceType,
            ip: info.ipAddress || '',
            loc: info.location || ''
          })
          new QRCode(el, {
            text: text,
            width: 180,
            height: 180,
            colorDark: '#1F2937',
            colorLight: '#ffffff'
          })
        })
      }).catch(() => { this.qrLoading = false })
    },
    // 下载二维码标签 PNG
    downloadQrcode() {
      const el = document.getElementById('qrContainer')
      const img = el ? el.querySelector('img') : null
      const src = img ? img.src : null
      if (!src) {
        this.$modal.msgWarning('二维码尚未生成，请稍后重试')
        return
      }
      const a = document.createElement('a')
      a.href = src
      a.download = '设备二维码_' + (this.qrDevice.deviceCode || 'device') + '.png'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      this.$modal.msgSuccess('标签已下载')
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            const payload = { ...this.form }
            // 清空保修期：原值存在但当前为空 → 传 clearWarranty 标记（后端 null 写入需绕过 MP 更新策略）
            if (!payload.warrantyExpire && this.oldWarranty) {
              payload.clearWarranty = true
            }
            updateDevice(payload).then(() => {
              this.$modal.msgSuccess('修改成功'); this.open = false; this.getList()
            })
          } else {
            addDevice(this.form).then(() => {
              this.$modal.msgSuccess('新增成功'); this.open = false; this.getList()
            })
          }
        }
      })
    },
    cancel() { this.open = false; this.reset() },
    reset() {
      this.form = { deviceType: undefined, status: 0, lineStatus: 0, gatewayId: undefined, tenantId: undefined, parentMainId: undefined, parentBackup1Id: undefined, parentBackup2Id: undefined, warrantyExpire: undefined }
      this.oldWarranty = undefined
      this.resetForm('form')
    },
    // ===== 三色状态 =====
    statusColor(row) {
      if (row.status === 0) return '#f56c6c'       // 红：离线
      if (row.lineStatus === 1) return '#909399'   // 灰：链路/业务异常
      return '#67c23a'                             // 绿：正常
    },
    statusColorOf(node) {
      if (node.status === 0) return '#f56c6c'
      if (node.lineStatus === 1) return '#909399'
      return '#67c23a'
    },
    statusTooltip(row) {
      if (row.status === 0) return '离线故障'
      if (row.lineStatus === 1) return '在线但链路/业务异常'
      return '在线正常'
    },
    // ===== 拓扑图布局 =====
    nodeX(id) {
      const idx = this.topoData.nodes.findIndex(n => n.id === id)
      const col = idx % 5
      return 80 + col * 155
    },
    nodeY(id) {
      const idx = this.topoData.nodes.findIndex(n => n.id === id)
      const row = Math.floor(idx / 5)
      return 70 + row * 100
    },
    linkColor(type) {
      return type === 'main' ? '#409eff' : '#e6a23c'
    },
    // ===== 类型字典 =====
    typeText(type) {
      return { network: '网络设备', camera: '视频摄像头', nvr: 'NVR录像机', door_controller: '门禁控制器' }[type] || type
    },
    typeTag(type) {
      return { network: 'primary', camera: 'success', nvr: 'warning', door_controller: 'info' }[type] || 'info'
    },
    // ===== 保修期状态 =====
    warrantyText(status) {
      return { in_warranty: '在保', expired: '已过保', none: '未设置' }[status] || '未设置'
    },
    warrantyTag(status) {
      return { in_warranty: 'success', expired: 'danger', none: 'info' }[status] || 'info'
    }
  }
}
</script>

<style scoped>
.status-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.15);
}
.topo-wrap {
  width: 100%;
  min-height: 420px;
  background: #f5f7fa;
  border-radius: 8px;
  overflow: auto;
}
.topo-preview-svg {
  display: block;
  width: 100%;
  height: auto;
  min-width: 780px;
}
.topo-empty {
  text-align: center;
  line-height: 420px;
  color: #909399;
}
/* 设备二维码弹窗（V1.2.7） */
.qr-empty {
  text-align: center;
  line-height: 220px;
  color: #909399;
}
.qr-body {
  display: flex;
  align-items: center;
}
.qr-left {
  flex: 0 0 200px;
  text-align: center;
}
.qr-canvas {
  display: inline-block;
  padding: 10px;
  border: 1px dashed #d1d5db;
  border-radius: 8px;
  background: #fff;
}
.qr-canvas img {
  display: block;
}
.qr-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.qr-right {
  flex: 1;
  margin-left: 16px;
}
.qr-info {
  background: #f5f6fa;
  border-radius: 8px;
  padding: 12px 14px;
}
.qr-row {
  font-size: 13px;
  color: #1f2937;
  line-height: 26px;
  display: flex;
}
.qr-label {
  flex: 0 0 64px;
  color: #6b7280;
}
</style>
