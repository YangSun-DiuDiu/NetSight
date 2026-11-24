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

    <!-- 设备清单由边缘网关本地页面维护，云端只读 -->
    <el-alert
      title="设备清单由边缘网关本地管理页面维护，云端仅展示，不支持新增/编辑/删除"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />

    <!-- 设备列表 -->
    <el-table border v-loading="loading" :data="deviceList">
      <el-table-column label="设备名称" prop="deviceName" width="100"  align="center"/>
      <el-table-column label="设备编码" prop="deviceCode" width="190"  align="center"/>
      <el-table-column label="设备类型" width="110" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="typeTag(scope.row.deviceType)">{{ typeText(scope.row.deviceType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="品牌/型号" min-width="140" align="center">
        <template slot-scope="scope">{{ scope.row.brand }} {{ scope.row.model }}</template>
      </el-table-column>
      <el-table-column label="管理IP" prop="ipAddress" width="130"  align="center"/>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="scope">
          <el-tooltip :content="statusTooltip(scope.row)" placement="top">
            <el-tag size="mini" :type="statusTagType(scope.row)">{{ statusTagText(scope.row) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="部署位置" prop="location" min-width="110" show-overflow-tooltip  align="center"/>
      <el-table-column label="保修期" width="140" align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.warrantyExpire" :style="{ marginRight: '4px' }">{{ scope.row.warrantyExpire }}</span>
          <el-tag size="mini" :type="warrantyTag(scope.row.warrantyStatus)">{{ warrantyText(scope.row.warrantyStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="归属网关" prop="gatewayName" min-width="110"  align="center"/>
      <el-table-column label="来源" width="90" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.source === 'edge' ? 'success' : 'info'">{{ scope.row.source === 'edge' ? '边缘网关' : '云端历史' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后同步" prop="syncedAt" width="150" align="center"/>
      <el-table-column label="上级设备" min-width="130" align="center">
        <template slot-scope="scope">
          <span v-if="!scope.row.parentNames || scope.row.parentNames.length === 0" style="color: #bbb">未配置</span>
          <el-tag v-for="(p, i) in scope.row.parentNames" :key="i" size="mini" style="margin-right: 4px" :type="i === 0 ? 'primary' : 'warning'">{{ p }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="100" class-name="small-padding fixed-width" fixed="right">
        <template slot-scope="scope">
          <el-button v-hasRole="['super_admin','tenant_admin','ops']" size="mini" type="text" icon="el-icon-camera" @click="handleQrcode(scope.row)">二维码</el-button>
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
import { listDevice, getQrcodeInfo } from '@/api/device/device'
import QRCode from 'qrcodejs2'

export default {
  name: 'Device',
  data() {
    return {
      loading: false,
      showSearch: true,
      deviceList: [],
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10, deviceName: undefined, deviceType: undefined, status: undefined },
      qrOpen: false,
      qrLoading: false,
      qrDevice: {},
      typeOptions: [
        { value: 'network', label: '网络设备' },
        { value: 'camera', label: '视频摄像头' },
        { value: 'nvr', label: 'NVR录像机' },
        { value: 'door_controller', label: '门禁控制器' }
      ]
    }
  },
  computed: {
    // 二维码类型文本（V1.2.7）
    qrTypeText() {
      return this.typeText(this.qrDevice.deviceType)
    }
  },
  created() {
    this.getList()
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
    handleQuery() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    // 自研拓扑预览 handleTopology() 已删除，待接入第三方拓扑组件后恢复入口

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
    // ===== 三色状态 =====
    statusTagType(row) {
      if (row.status === 0) return 'danger'      // 红：离线
      if (row.lineStatus === 1) return 'warning'  // 黄：链路/业务异常
      return 'success'                            // 绿：正常
    },
    statusTagText(row) {
      if (row.status === 0) return '离线'
      if (row.lineStatus === 1) return '链路异常'
      return '在线'
    },
    statusColor(row) {
      if (row.status === 0) return '#f56c6c'       // 红：离线
      if (row.lineStatus === 1) return '#909399'   // 灰：链路/业务异常
      return '#67c23a'                             // 绿：正常
    },
    statusTooltip(row) {
      if (row.status === 0) return '离线故障'
      if (row.lineStatus === 1) return '在线但链路/业务异常'
      return '在线正常'
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
