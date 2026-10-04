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
          :max-size="5 * 1024 * 1024"
          @after-read="afterRead"
        />
      </div>

      <div class="upload-section">
        <div class="upload-label">领用备件（可选）</div>
        <van-cell title="选择备件" is-link @click="showPartPicker = true">
          <span v-if="selectedPart" class="part-selected">{{ selectedPart.partName }} x{{ form.partQty }}</span>
        </van-cell>
      </div>

      <div class="submit-bar">
        <van-button block class="lavender-btn" :loading="submitting" @click="submitComplete">提交完工</van-button>
      </div>
    </div>

    <van-popup v-model="showPartPicker" position="bottom" :style="{ maxHeight: '60%' }">
      <van-picker
        :columns="partOptions"
        @confirm="onPartConfirm"
        @cancel="showPartPicker = false"
      />
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
      form: {
        repairResult: '',
        partId: null,
        partQty: 1
      }
    }
  },
  created() {
    this.orderId = this.$route.params.id
    this.loadDetail()
    this.loadParts()
  },
  methods: {
    loadDetail() {
      request.get('/m/order/' + this.orderId).then(res => {
        this.order = res.data.order
        this.records = res.data.records || []
        this.parts = res.data.parts || []
        if (this.order.repairPhotos) {
          try {
            this.photoList = JSON.parse(this.order.repairPhotos)
          } catch (e) {
            this.photoList = []
          }
        }
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
    onPartConfirm(value) {
      this.selectedPart = value
      this.form.partId = value.value
      this.form.partQty = 1
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
        photos: photos,
        parts: this.form.partId ? [{ partId: this.form.partId, quantity: this.form.partQty }] : []
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
}
::v-deep .light-navbar .van-nav-bar__title {
  color: var(--ink);
  font-weight: 600;
}
::v-deep .light-navbar::after {
  background-color: var(--hairline);
}

/* Sections */
.section {
  margin-top: 8px;
}
.section-title {
  padding: 16px 16px 8px;
  font-size: 13px;
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

/* Upload section */
.upload-section {
  margin: 8px 16px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 12px;
  padding: 16px;
}
.upload-label {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 12px;
  color: var(--ink);
}
.part-selected {
  color: var(--primary);
  font-size: 14px;
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
  font-size: 15px;
  font-weight: 500;
}
::v-deep .lavender-btn:active {
  background: #828fff;
}
</style>
