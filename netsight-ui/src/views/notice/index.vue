<template>
  <div class="app-container">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#EAF0FB;color:#205CF5">
            <i class="el-icon-message" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.total || 0 }}</div>
            <div class="stat-label">公告总数</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#E8F8F2;color:#10B981">
            <i class="el-icon-position" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.published || 0 }}</div>
            <div class="stat-label">已发布</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#FDF3E7;color:#F59E0B">
            <i class="el-icon-edit-outline" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.draft || 0 }}</div>
            <div class="stat-label">草稿</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card">
          <div class="stat-icon" style="background:#F3F4F6;color:#6B7280">
            <i class="el-icon-circle-close" />
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ stats.offline || 0 }}</div>
            <div class="stat-label">已下线</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 搜索区 -->
    <el-form :model="queryParams" ref="queryForm" :inline="true" v-show="showSearch">
      <el-form-item label="公告标题" prop="title">
        <el-input v-model="queryParams.title" placeholder="请输入公告标题" clearable size="small" style="width: 180px" @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="公告状态" clearable size="small" style="width: 120px">
          <el-option label="草稿" :value="0" />
          <el-option label="已发布" :value="1" />
          <el-option label="已下线" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="类型" prop="noticeType">
        <el-select v-model="queryParams.noticeType" placeholder="公告类型" clearable size="small" style="width: 120px">
          <el-option label="公告" value="notice" />
          <el-option label="通知" value="notify" />
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
        <el-button v-hasPermi="['notice:add']" type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增公告</el-button>
      </el-col>
    </el-row>

    <!-- 公告列表 -->
    <el-table border v-loading="loading" :data="noticeList">
      <el-table-column label="标题" prop="title" min-width="100" show-overflow-tooltip align="center">
        <template slot-scope="scope">
          <span v-if="scope.row.isTop === 1" class="top-flag">置顶</span>
          <span :class="{ 'unread-title': scope.row.status === 1 }">{{ scope.row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="180" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.noticeType === 'notice' ? 'primary' : 'warning'" effect="light">{{ scope.row.noticeType === 'notice' ? '公告' : '通知' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="180" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.level === 'urgent' ? 'danger' : (scope.row.level === 'important' ? 'warning' : 'info')">{{ levelText(scope.row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="180" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="scope.row.status === 1 ? 'success' : (scope.row.status === 0 ? 'info' : 'danger')">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布人" prop="publisherName" width="180" align="center" />
      <el-table-column label="发布时间" prop="publishTime" width="180" align="center" />
      <el-table-column label="已读" prop="readCount" width="180" align="center" />
      <el-table-column label="操作" align="center" width="220" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button type="text" size="mini" icon="el-icon-view" @click="handleDetail(scope.row)">详情</el-button>
          <el-button v-hasPermi="['notice:publish']" v-if="scope.row.status !== 1" type="text" size="mini" icon="el-icon-upload2" @click="handlePublish(scope.row)">发布</el-button>
          <el-button v-hasPermi="['notice:publish']" v-if="scope.row.status === 1" type="text" size="mini" icon="el-icon-download" @click="handleOffline(scope.row)">下线</el-button>
          <el-button v-hasPermi="['notice:edit']" type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
          <el-button v-hasPermi="['notice:remove']" type="text" size="mini" icon="el-icon-delete" class="el-button--text-danger" @click="handleDelete(scope.row)" style="color:#f56c6c">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <!-- 新增/修改弹窗 -->
    <el-dialog :title="noticeForm.id ? '修改公告' : '新增公告'" :visible.sync="open" width="640px" append-to-body :close-on-click-modal="false">
      <el-form ref="noticeForm" :model="noticeForm" :rules="noticeRules" label-width="90px">
        <el-form-item label="公告标题" prop="title">
          <el-input v-model="noticeForm.title" placeholder="请输入公告标题" maxlength="128" />
        </el-form-item>
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="类型" prop="noticeType">
              <el-select v-model="noticeForm.noticeType" style="width: 100%">
                <el-option label="公告" value="notice" />
                <el-option label="通知" value="notify" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="级别" prop="level">
              <el-select v-model="noticeForm.level" style="width: 100%">
                <el-option label="普通" value="normal" />
                <el-option label="重要" value="important" />
                <el-option label="紧急" value="urgent" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="置顶" prop="isTop">
              <el-switch v-model="noticeForm.isTop" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="过期时间" prop="expireTime">
          <el-date-picker v-model="noticeForm.expireTime" type="datetime" placeholder="选择过期时间（留空=永久有效）" style="width: 100%" value-format="yyyy-MM-dd HH:mm:ss" />
        </el-form-item>
        <el-form-item label="公告内容" prop="content">
          <el-input v-model="noticeForm.content" type="textarea" :rows="6" placeholder="请输入公告内容" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确 定</el-button>
        <el-button @click="open = false">取 消</el-button>
      </div>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog title="公告详情" :visible.sync="detailOpen" width="640px" append-to-body :close-on-click-modal="false">
      <el-descriptions :column="2" border size="medium">
        <el-descriptions-item label="标题" :span="2">{{ detailForm.title }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detailForm.noticeType === 'notice' ? '公告' : '通知' }}</el-descriptions-item>
        <el-descriptions-item label="级别">{{ levelText(detailForm.level) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(detailForm.status) }}</el-descriptions-item>
        <el-descriptions-item label="置顶">{{ detailForm.isTop === 1 ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="发布人">{{ detailForm.publisherName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ detailForm.publishTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="过期时间">{{ detailForm.expireTime || '永久有效' }}</el-descriptions-item>
        <el-descriptions-item label="已读数">{{ detailForm.readCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">
          <div style="white-space: pre-wrap; line-height: 1.8;">{{ detailForm.content }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script>
import { listNotice, getNoticeStats, addNotice, updateNotice, delNotice, publishNotice, offlineNotice } from '@/api/notice/notice'
import Pagination from '@/components/Pagination'

export default {
  name: 'Notice',
  components: { Pagination },
  data() {
    return {
      loading: false,
      showSearch: true,
      noticeList: [],
      total: 0,
      stats: {},
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        title: undefined,
        status: undefined,
        noticeType: undefined
      },
      open: false,
      detailOpen: false,
      submitLoading: false,
      noticeForm: {
        id: undefined,
        title: '',
        content: '',
        noticeType: 'notice',
        level: 'normal',
        status: 0,
        isTop: 0,
        expireTime: undefined
      },
      detailForm: {},
      noticeRules: {
        title: [{ required: true, message: '公告标题不能为空', trigger: 'blur' }],
        content: [{ required: true, message: '公告内容不能为空', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.getList()
    this.getStats()
  },
  methods: {
    getList() {
      this.loading = true
      listNotice(this.queryParams).then(res => {
        this.noticeList = res.rows || []
        this.total = Number(res.total || 0)
        this.loading = false
      }).catch(() => { this.loading = false })
    },
    getStats() {
      getNoticeStats().then(res => {
        this.stats = res || {}
      }).catch(() => {})
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.handleQuery()
    },
    resetForm(refName) {
      if (this.$refs[refName]) {
        this.$refs[refName].resetFields()
      }
    },
    handleAdd() {
      this.noticeForm = {
        id: undefined,
        title: '',
        content: '',
        noticeType: 'notice',
        level: 'normal',
        status: 0,
        isTop: 0,
        expireTime: undefined
      }
      this.open = true
    },
    handleUpdate(row) {
      this.noticeForm = {
        id: row.id,
        title: row.title,
        content: row.content,
        noticeType: row.noticeType,
        level: row.level,
        status: row.status,
        isTop: row.isTop,
        expireTime: row.expireTime
      }
      this.open = true
    },
    handleDetail(row) {
      this.detailForm = { ...row }
      this.detailOpen = true
    },
    submitForm() {
      this.$refs.noticeForm.validate(valid => {
        if (!valid) return
        this.submitLoading = true
        const request = this.noticeForm.id ? updateNotice(this.noticeForm) : addNotice(this.noticeForm)
        request.then(() => {
          this.$modal.msgSuccess(this.noticeForm.id ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
          this.getStats()
        }).finally(() => {
          this.submitLoading = false
        })
      })
    },
    handlePublish(row) {
      this.$modal.confirm('确定发布公告「' + row.title + '」吗？发布后所有用户可见。').then(() => {
        return publishNotice(row.id)
      }).then(() => {
        this.$modal.msgSuccess('发布成功')
        this.getList()
        this.getStats()
      }).catch(() => {})
    },
    handleOffline(row) {
      this.$modal.confirm('确定下线公告「' + row.title + '」吗？下线后用户端不可见。').then(() => {
        return offlineNotice(row.id)
      }).then(() => {
        this.$modal.msgSuccess('下线成功')
        this.getList()
        this.getStats()
      }).catch(() => {})
    },
    handleDelete(row) {
      this.$modal.confirm('确定删除公告「' + row.title + '」吗？').then(() => {
        return delNotice(row.id)
      }).then(() => {
        this.$modal.msgSuccess('删除成功')
        this.getList()
        this.getStats()
      }).catch(() => {})
    },
    statusText(status) {
      return status === 1 ? '已发布' : (status === 0 ? '草稿' : '已下线')
    },
    levelText(level) {
      return level === 'urgent' ? '紧急' : (level === 'important' ? '重要' : '普通')
    }
  }
}
</script>

<style lang="scss" scoped>
.top-flag {
  display: inline-block;
  background: #205CF5;
  color: #fff;
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 4px;
  margin-right: 6px;
  vertical-align: middle;
}

.unread-title {
  font-weight: 600;
}
</style>
