<template>
  <div class="m-order-detail linear-theme">
    <van-nav-bar
      title="工单详情"
      left-arrow
      @click-left="$router.back()"
      class="light-navbar"
    />

    <div class="section">
      <div class="section-title">工单信息</div>
      <van-cell-group inset class="light-group">
        <van-cell title="工单号" :value="order.orderNo" />
        <van-cell title="设备名称" :value="order.deviceName" />
        <van-cell title="设备位置" :value="order.deviceLocation" />
        <van-cell title="故障类型" :value="faultText(order.faultType)" />
        <van-cell title="故障描述" :label="order.description" />
        <van-cell title="状态">
          <van-tag :type="statusType(order.status)">{{ order.statusText }}</van-tag>
        </van-cell>
      </van-cell-group>
    </div>

    <div class="section">
      <div class="section-title">AI 诊断</div>
      <div class="ai-card">
        <van-field
          v-model="aiForm.question"
          rows="3"
          type="textarea"
          label="故障现象"
          placeholder="可补充或修改故障现象，例如：指示灯不亮、网络不通、无法开门…"
          class="ai-input"
        />
        <div class="ai-ctx" v-if="aiCtxText">自动带入：{{ aiCtxText }}</div>
        <div class="ai-actions">
          <van-button
            size="small"
            block
            class="ai-btn"
            :loading="aiLoading"
            :disabled="!order.deviceName && !aiForm.question"
            @click="aiAsk"
          >AI 分析</van-button>
        </div>

        <div class="ai-result" v-if="aiResult">
          <div class="ai-badge" :class="aiBadgeClass">{{ aiBadgeText }}</div>
          <div class="ai-answer">{{ aiResult.answer }}</div>
          <div class="ai-sources" v-if="aiResult.sources && aiResult.sources.length">
            <div class="ai-sources-title">参考文章</div>
            <van-tag
              v-for="(s, i) in aiResult.sources"
              :key="i"
              plain
              round
              class="ai-source-tag"
            >{{ s.title }}</van-tag>
          </div>
        </div>
      </div>
    </div>

    <div class="section">
      <div class="section-title">处理记录</div>
      <van-steps :active="records.length" direction="vertical">
        <van-step v-for="(r, i) in records" :key="i">
          <h3>{{ r.content }}</h3>
          <p>{{ r.operator }} · {{ r.createTime }}</p>
        </van-step>
      </van-steps>
    </div>

    <div class="section" v-if="parts.length > 0">
      <div class="section-title">已领备件</div>
      <van-cell-group inset class="light-group">
        <van-cell v-for="(p, i) in parts" :key="i" :title="p.partName" :value="'x' + p.quantity + ' ' + p.unit" />
      </van-cell-group>
    </div>

    <div class="section" v-if="order.repairPhotos && photoList.length > 0">
      <div class="section-title">维修照片</div>
      <div class="photo-grid">
        <img v-for="(p, i) in photoList" :key="i" :src="p" @click="previewPhoto(p)" />
      </div>
    </div>

    <div class="action-bar" v-if="order.status === 1">
      <van-button block class="lavender-btn" :loading="starting" @click="startRepair">开始维修</van-button>
    </div>

    <div class="section" v-if="order.status === 2">
      <div class="section-title">维修反馈</div>
      <van-cell-group inset class="light-group">
        <van-field
          v-model="form.repairResult"
          rows="3"
          type="textarea"
          label="维修结果"
          placeholder="请描述维修过程和结果"
          required
        />
      </van-cell-group>

      <div class="upload-section">
        <div class="upload-label">维修照片（必传）</div>
        <van-uploader
          v-model="fileList"
          :max-count="6"
          :max-size="10 * 1024 * 1024"
          :after-read="afterRead"
          @oversize="onOversize"
        />
      </div>

      <div class="upload-section">
        <div class="upload-label">领料申请（提交后进入审批）</div>
        <van-cell title="选择备件" is-link @click="showPartPicker = true">
          <span v-if="selectedPart" class="part-selected">{{ selectedPart.partName }} x{{ outQty }}</span>
          <span v-else class="part-placeholder">未选择</span>
        </van-cell>
        <div class="qty-row" v-if="selectedPart">
          <span class="qty-label">数量</span>
          <van-stepper v-model="outQty" :min="1" :max="selectedPart.quantity" integer />
          <span class="qty-stock">库存 {{ selectedPart.quantity }}{{ selectedPart.unit }}</span>
        </div>
        <van-button
          block
          class="out-btn"
          :loading="outSubmitting"
          :disabled="!selectedPart"
          @click="submitOutRequest"
        >提交领料申请</van-button>
      </div>

      <div class="section" v-if="outRequests.length > 0">
        <div class="section-title">领料记录</div>
        <van-cell-group inset class="light-group">
          <van-cell v-for="(o, i) in outRequests" :key="i">
            <template slot="title">
              <div class="out-item">
                <span class="out-biz">{{ o.bizId }}</span>
                <van-tag :type="outStatusType(o.approvalStatus)">{{ outStatusText(o.approvalStatus) }}</van-tag>
              </div>
            </template>
            <template slot="label">
              <div class="out-items">{{ outItemsText(o.items) }}</div>
              <div class="out-time">{{ o.createTime }}<span v-if="o.remark" class="out-remark"> · {{ o.remark }}</span></div>
            </template>
            <van-button
              v-if="o.approvalStatus === 'rejected'"
              size="mini"
              plain
              type="primary"
              class="out-resubmit"
              :loading="resubmittingBiz === o.bizId"
              @click="resubmitOut(o.bizId)"
            >重新提交</van-button>
          </van-cell>
        </van-cell-group>
      </div>

      <div class="submit-bar">
        <van-button block class="lavender-btn" :loading="submitting" @click="submitComplete">提交完工</van-button>
      </div>
    </div>

    <van-popup v-model="showPartPicker" position="bottom" :style="{ maxHeight: '60%' }">
      <div class="part-picker-title">选择备件</div>
      <van-cell
        v-for="(p, i) in partOptions"
        :key="i"
        :title="p.text"
        is-link
        @click="onPartPick(p)"
      />
      <div class="part-picker-cancel" @click="showPartPicker = false">取消</div>
    </van-popup>

    <van-image-preview v-model="showPhotoPreview" :images="photoList" />
  </div>
</template>

<script>
import request from '@/utils/request'
import { getToken } from '@/utils/auth'
import { Toast } from 'vant'

export default {
  name: 'MOrderDetail',
  data() {
    return {
      order: {},
      records: [],
      parts: [],
      photoList: [],
      starting: false,
      submitting: false,
      showPartPicker: false,
      showPhotoPreview: false,
      fileList: [],
      partOptions: [],
      selectedPart: null,
      outRequests: [],
      outQty: 1,
      outSubmitting: false,
      resubmittingBiz: '',
      aiLoading: false,
      aiResult: null,
      aiForm: {
        question: ''
      },
      form: {
        repairResult: '',
        partId: null,
        partQty: 1
      }
    }
  },
  computed: {
    aiCtxText() {
      const parts = []
      if (this.order.deviceName) parts.push(this.order.deviceName)
      if (this.order.deviceLocation) parts.push(this.order.deviceLocation)
      if (this.order.faultType) parts.push(this.faultText(this.order.faultType))
      return parts.join(' / ')
    },
    aiBadgeText() {
      if (!this.aiResult) return ''
      if (this.aiResult.noHit) return '知识库暂无匹配'
      if (!this.aiResult.aiUsed) return 'AI 服务暂不可用 · 知识库原文'
      if (this.aiResult.fromCache) return 'AI 生成 · 缓存'
      return 'AI 生成'
    },
    aiBadgeClass() {
      if (!this.aiResult) return ''
      if (this.aiResult.noHit) return 'badge-muted'
      if (!this.aiResult.aiUsed) return 'badge-fallback'
      return 'badge-ai'
    }
  },
  created() {
    this.orderId = this.$route.params.id
    this.loadDetail()
    this.loadParts()
    this.loadOutRequests()
  },
  methods: {
    loadDetail() {
      request.get('/m/order/' + this.orderId).then(res => {
        this.order = res.data.order
        this.records = res.data.records || []
        this.parts = res.data.parts || []
        // 故障现象默认带入工单描述，维修人员可补充修改
        this.aiForm.question = this.order.description || ''
        if (this.order.repairPhotos) {
          try {
            this.photoList = JSON.parse(this.order.repairPhotos)
          } catch (e) {
            this.photoList = []
          }
        }
      })
    },
    aiAsk() {
      const question = (this.aiForm.question || '').trim()
      if (!question) {
        Toast('请描述故障现象')
        return
      }
      this.aiLoading = true
      const body = {
        question: question,
        deviceName: this.order.deviceName || '',
        deviceType: this.order.deviceType || '',
        deviceLocation: this.order.deviceLocation || '',
        faultType: this.order.faultType || '',
        description: this.order.description || ''
      }
      request.post('/ai/knowledge/ask', body).then(res => {
        this.aiLoading = false
        this.aiResult = res.data
      }).catch(err => {
        this.aiLoading = false
        Toast((err && err.msg) || 'AI 问诊失败，请稍后重试')
      })
    },
    loadParts() {
      request.get('/m/order/parts').then(res => {
        this.partOptions = (res.data || []).map(p => ({
          text: p.partType + ' ' + p.brand + ' ' + p.model + '（库存' + p.quantity + p.unit + '）',
          value: p.id,
          ...p
        }))
      })
    },
    loadOutRequests() {
      request.get('/spare/out/order/' + this.orderId).then(res => {
        this.outRequests = res.data || []
      }).catch(() => {
        this.outRequests = []
      })
    },
    submitOutRequest() {
      if (!this.selectedPart) {
        Toast('请选择备件')
        return
      }
      if (!this.outQty || this.outQty < 1) {
        Toast('数量必须大于0')
        return
      }
      this.outSubmitting = true
      request.post('/spare/out/request', {
        orderId: this.orderId,
        items: [{ partId: this.selectedPart.value, quantity: this.outQty }],
        remark: 'H5 领料申请'
      }).then(res => {
        this.outSubmitting = false
        Toast('领料申请已提交，等待审批')
        this.loadOutRequests()
        this.loadDetail()
        this.selectedPart = null
        this.outQty = 1
      }).catch(err => {
        this.outSubmitting = false
        Toast((err && err.msg) || '提交失败，请稍后重试')
      })
    },
    resubmitOut(bizId) {
      this.resubmittingBiz = bizId
      request.post('/spare/out/resubmit', { bizId: bizId }).then(() => {
        this.resubmittingBiz = ''
        Toast('已重新提交，等待审批')
        this.loadOutRequests()
      }).catch(err => {
        this.resubmittingBiz = ''
        Toast((err && err.msg) || '重新提交失败')
      })
    },
    outItemsText(items) {
      if (!items) return ''
      try {
        const arr = JSON.parse(items)
        return arr.map(i => (i.partName || i.partId) + ' x' + i.quantity).join('，')
      } catch (e) {
        return items
      }
    },
    outStatusText(status) {
      const map = { processing: '审批中', approved: '已通过', rejected: '已驳回', canceled: '已撤销' }
      return map[status] || status
    },
    outStatusType(status) {
      const map = { processing: 'warning', approved: 'success', rejected: 'danger', canceled: 'default' }
      return map[status] || 'default'
    },
    startRepair() {
      this.starting = true
      request.post('/m/order/' + this.orderId + '/start').then(() => {
        this.starting = false
        Toast('已开始维修')
        this.loadDetail()
      }).catch(() => {
        this.starting = false
      })
    },
    afterRead(file) {
      const items = Array.isArray(file) ? file : [file]
      items.forEach(item => this.uploadPhoto(item))
    },
    onOversize(file) {
      Toast('照片超过 10MB，请压缩后重传')
    },
    uploadPhoto(item) {
      const f = item.file
      if (!f) {
        item.status = 'failed'
        item.message = '文件读取失败'
        return
      }
      const formData = new FormData()
      formData.append('file', f)
      const token = getToken()
      item.status = 'uploading'
      fetch('/prod-api/file/upload', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token },
        body: formData
      }).then(r => r.json()).then(res => {
        if (res.code === 200) {
          this.$set(item, 'url', res.data.url)
          item.status = 'done'
          item.message = ''
        } else {
          item.status = 'failed'
          item.message = res.msg || '上传失败'
          Toast(res.msg || '上传失败')
        }
      }).catch(() => {
        item.status = 'failed'
        item.message = '网络错误'
        Toast('照片上传失败，请检查网络后重试')
      })
    },
    onPartPick(p) {
      this.selectedPart = p
      this.outQty = 1
      this.showPartPicker = false
    },
    submitComplete() {
      if (!this.form.repairResult) {
        Toast('请填写维修结果')
        return
      }
      const uploading = this.fileList.find(f => f.status === 'uploading')
      if (uploading) {
        Toast('照片正在上传，请稍候再提交')
        return
      }
      const failed = this.fileList.find(f => f.status === 'failed')
      if (failed) {
        Toast('有照片上传失败，请删除后重新上传')
        return
      }
      const photos = this.fileList
        .filter(f => f.status === 'done' && f.url)
        .map(f => f.url)
      if (photos.length === 0) {
        Toast('请至少上传一张维修照片')
        return
      }
      this.submitting = true
      const body = {
        repairResult: this.form.repairResult,
        photos: photos
        // 备件领用已迁移至出库申请审批链路（/spare/out/request），完工不再内联扣库存
      }
      request.post('/m/order/' + this.orderId + '/complete', body).then(() => {
        this.submitting = false
        Toast('完工提交成功')
        this.loadDetail()
      }).catch(() => {
        this.submitting = false
      })
    },
    previewPhoto(url) {
      this.showPhotoPreview = true
    },
    statusType(status) {
      const map = { 1: 'warning', 2: 'primary', 3: 'success', 5: 'default' }
      return map[status] || 'default'
    },
    faultText(type) {
      const map = { offline: '设备离线', line_abnormal: '链路异常', manual: '手动建单' }
      return map[type] || type
    }
  }
}
</script>

<style scoped>
.linear-theme {
  --canvas: #f7f8f8;
  --surface: #ffffff;
  --hairline: #e6e7ea;
  --ink: #1f2937;
  --ink-muted: #6b7280;
  --ink-subtle: #9ca3af;
  --primary: #5e6ad2;

  min-height: 100vh;
  background: var(--canvas);
  padding-bottom: 80px;
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', system-ui, sans-serif;
}

/* Navbar */
::v-deep .light-navbar {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 1px 0 0 var(--hairline);
}
::v-deep .light-navbar .van-nav-bar__title {
  color: var(--ink);
  font-weight: 600;
}
::v-deep .light-navbar .van-icon {
  color: var(--ink) !important;
  font-size: 20px !important;
  padding: 6px;
}
::v-deep .light-navbar::after {
  background-color: transparent;
}

/* Sections */
.section {
  margin-top: 8px;
}
.section-title {
  padding: 16px 16px 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-subtle);
  text-transform: uppercase;
  letter-spacing: 0.4px;
}

/* Light cells */
::v-deep .light-group {
  background: var(--surface);
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--hairline);
}

/* AI 诊断卡片 */
.ai-card {
  margin: 0 16px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
  padding: 12px;
}
::v-deep .ai-input {
  background: var(--surface);
  border-radius: 8px;
}
::v-deep .ai-input .van-field__label {
  color: var(--ink);
  font-weight: 500;
}
.ai-ctx {
  margin: 4px 16px 0;
  font-size: 13px;
  color: var(--ink-subtle);
}
.ai-actions {
  margin: 12px 8px 4px;
}
::v-deep .ai-btn {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  height: 42px;
  font-size: 15px;
  font-weight: 500;
}
.ai-result {
  margin-top: 12px;
  border-top: 1px dashed var(--hairline);
  padding-top: 12px;
}
.ai-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 20px;
  font-size: 13px;
  margin-bottom: 8px;
}
.badge-ai {
  background: #eef0ff;
  color: var(--primary);
}
.badge-fallback {
  background: #fff7e6;
  color: #b45309;
}
.badge-muted {
  background: #f3f4f6;
  color: var(--ink-muted);
}
.ai-answer {
  font-size: 15px;
  line-height: 1.7;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-sources {
  margin-top: 10px;
}
.ai-sources-title {
  font-size: 13px;
  color: var(--ink-subtle);
  margin-bottom: 6px;
}
.ai-source-tag {
  margin: 0 6px 6px 0;
  color: var(--primary);
  border-color: #c7cbe8;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Upload section */
.upload-section {
  margin: 8px 16px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
  padding: 16px;
}
.upload-label {
  font-size: 15px;
  font-weight: 500;
  margin-bottom: 12px;
  color: var(--ink);
}
.part-selected {
  color: var(--primary);
  font-size: 15px;
}
.part-placeholder {
  color: var(--ink-subtle);
  font-size: 15px;
}
.qty-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 4px 0;
}
.qty-label {
  font-size: 14px;
  color: var(--ink);
  font-weight: 500;
}
.qty-stock {
  font-size: 13px;
  color: var(--ink-subtle);
  margin-left: auto;
}
::v-deep .out-btn {
  margin-top: 16px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  height: 42px;
  font-size: 15px;
  font-weight: 500;
}
.out-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.out-biz {
  font-family: ui-monospace, 'SF Mono', Menlo, monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.out-items {
  font-size: 13px;
  color: var(--ink-muted);
}
.out-time {
  font-size: 12px;
  color: var(--ink-subtle);
  margin-top: 2px;
}
.out-remark {
  color: #b45309;
}
.out-resubmit {
  float: right;
  margin-top: 6px;
  border-color: var(--primary);
  color: var(--primary);
}
.part-picker-title {
  padding: 16px 16px 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.part-picker-cancel {
  text-align: center;
  padding: 14px 0;
  font-size: 15px;
  color: var(--ink-muted);
  border-top: 1px solid var(--hairline);
}

/* Action bar */
.action-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 16px;
  background: var(--surface);
  border-top: 1px solid var(--hairline);
}
.submit-bar {
  margin: 16px;
}

/* Photos */
.photo-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 16px;
}
.photo-grid img {
  width: 100px;
  height: 100px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--hairline);
}

/* Lavender button */
::v-deep .lavender-btn {
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  height: 44px;
  font-size: 16px;
  font-weight: 500;
}
::v-deep .lavender-btn:active {
  background: #828fff;
}
</style>
