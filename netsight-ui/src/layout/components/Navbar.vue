<template>
  <div class="navbar" :class="'nav' + navType">
    <div class="navbar-brand">
      <img class="navbar-logo" src="@/assets/logo/logo-a-eye.png" alt="NetSight" />
      <span class="navbar-title">NetSight</span>
    </div>
    <hamburger id="hamburger-container" :is-active="sidebar.opened" class="hamburger-container" @toggleClick="toggleSideBar" />

    <breadcrumb v-if="navType == 1" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="navType == 2" id="topmenu-container" class="topmenu-container" />
    <template v-if="navType == 3">
      <logo v-show="showLogo" :collapse="false"></logo>
      <top-bar id="topbar-container" class="topbar-container" />
    </template>
    <div class="right-menu">
      <div class="live-status right-menu-item" title="云端服务运行状态">
        <span class="live-dot"></span>
        <span class="live-text">云端在线</span>
      </div>
      <el-tooltip content="通知公告" effect="dark" placement="bottom">
        <div id="notice-bell" class="right-menu-item hover-effect notice-btn" @click="openNoticeCenter">
          <el-badge :value="noticeUnread" :hidden="noticeUnread <= 0" :max="99" class="notice-badge">
            <i class="el-icon-bell" />
          </el-badge>
        </div>
      </el-tooltip>
      <el-tooltip content="统一待办" effect="dark" placement="bottom">
        <div id="todo-bell" class="right-menu-item hover-effect notice-btn" @click="goTodo">
          <el-badge :value="todoTotal" :hidden="todoTotal <= 0" :max="99" class="notice-badge">
            <i class="el-icon-s-order" />
          </el-badge>
        </div>
      </el-tooltip>
      <template v-if="device!=='mobile'">
        <search id="header-search" class="right-menu-item" />

        <screenfull id="screenfull" class="right-menu-item hover-effect" />

        <el-tooltip content="布局大小" effect="dark" placement="bottom">
          <size-select id="size-select" class="right-menu-item hover-effect" />
        </el-tooltip>
      </template>

      <el-tooltip content="帮助中心" effect="dark" placement="bottom">
        <div id="help-center" class="right-menu-item hover-effect help-btn" @click="helpVisible = true">
          <i class="el-icon-question" />
          <span>帮助中心</span>
        </div>
      </el-tooltip>

      <el-dropdown class="account-container right-menu-item hover-effect" trigger="click">
        <div class="account-wrapper">
          <span class="account-name">{{ name }}</span>
          <i class="el-icon-caret-bottom account-caret" />
        </div>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item divided @click.native="logout">
            <span>退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>

    <el-dialog title="帮助中心" :visible.sync="helpVisible" width="440px" append-to-body :close-on-click-modal="false">
      <el-descriptions :column="1" border size="medium">
        <el-descriptions-item v-for="item in helpInfo" :key="item.label" :label="item.label">
          {{ item.value }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 通知中心弹窗 -->
    <el-dialog title="通知中心" :visible.sync="noticeVisible" width="520px" append-to-body :close-on-click-modal="false">
      <div v-loading="noticeLoading" class="notice-center">
        <div v-if="noticeList.length === 0 && !noticeLoading" class="notice-empty">
          <i class="el-icon-bell" />
          <p>暂无公告通知</p>
        </div>
        <div v-for="item in noticeList" :key="item.id" class="notice-item" :class="{ 'notice-item-unread': item.readFlag === 0 }" @click="viewNotice(item)">
          <div class="notice-item-head">
            <span v-if="item.isTop === 1" class="notice-top">置顶</span>
            <el-tag size="mini" :type="item.level === 'urgent' ? 'danger' : (item.level === 'important' ? 'warning' : 'info')" effect="light">{{ levelText(item.level) }}</el-tag>
            <span class="notice-item-title">{{ item.title }}</span>
            <span v-if="item.readFlag === 0" class="notice-dot"></span>
          </div>
          <div class="notice-item-meta">{{ item.noticeType === 'notice' ? '公告' : '通知' }} · {{ item.publisherName || '系统' }} · {{ item.publishTime }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- 公告详情弹窗 -->
    <el-dialog title="公告详情" :visible.sync="noticeDetailVisible" width="560px" append-to-body :close-on-click-modal="false">
      <div class="notice-detail">
        <div class="notice-detail-title">{{ noticeDetail.title }}</div>
        <div class="notice-detail-meta">{{ levelText(noticeDetail.level) }} · {{ noticeDetail.noticeType === 'notice' ? '公告' : '通知' }} · {{ noticeDetail.publisherName || '系统' }} · {{ noticeDetail.publishTime }}</div>
        <div class="notice-detail-content">{{ noticeDetail.content }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import TopNav from '@/components/TopNav'
import TopBar from './TopBar'
import Logo from './Sidebar/Logo'
import Hamburger from '@/components/Hamburger'
import Screenfull from '@/components/Screenfull'
import SizeSelect from '@/components/SizeSelect'
import Search from '@/components/HeaderSearch'
import { listPublishedNotice, getUnreadNoticeCount, getNotice } from '@/api/notice/notice'
import { getTodoStats } from '@/api/todo/todo'

export default {
  emits: ['setLayout'],
  components: {
    Breadcrumb,
    Logo,
    TopNav,
    TopBar,
    Hamburger,
    Screenfull,
    SizeSelect,
    Search
  },
  data() {
    return {
      helpVisible: false,
      // 通知中心
      noticeVisible: false,
      noticeLoading: false,
      noticeUnread: 0,
      noticeList: [],
      noticeDetailVisible: false,
      noticeDetail: {},
      noticeTimer: null,
      // 统一待办角标
      todoTotal: 0,
      todoTimer: null,
      // 帮助中心信息（部署时可在此维护版本/开发者/备案/文档）
      helpInfo: [
        { label: '系统名称', value: 'NetSight 网络资产监控管理系统' },
        { label: '版本号', value: 'v1.2.3' },
        { label: '开发者', value: 'NetSight 项目组' },
        { label: '备案号', value: '待备案' },
        { label: '使用文档', value: '《网络资产监控管理系统 V1.1 建设方案》' }
      ]
    }
  },
  computed: {
    ...mapGetters([
      'sidebar',
      'device',
      'name'
    ]),
    navType: {
      get() {
        return this.$store.state.settings.navType
      }
    },
    showLogo: {
      get() {
        return this.$store.state.settings.sidebarLogo
      }
    }
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    // 通知中心：加载未读数与公告列表
    loadUnread() {
      getUnreadNoticeCount().then(res => {
        this.noticeUnread = Number(res || 0)
      }).catch(() => {})
    },
    loadPublished() {
      listPublishedNotice().then(res => {
        this.noticeList = res || []
      }).catch(() => {})
    },
    openNoticeCenter() {
      this.noticeVisible = true
      this.noticeLoading = true
      this.loadPublished().finally(() => {
        this.noticeLoading = false
        this.loadUnread()
      })
    },
    viewNotice(item) {
      getNotice(item.id).then(res => {
        this.noticeDetail = res || {}
        this.noticeDetailVisible = true
        // 未读 → 已读：刷新列表与角标
        if (item.readFlag === 0) {
          item.readFlag = 1
          this.loadUnread()
          this.loadPublished()
        }
      }).catch(() => {})
    },
    levelText(level) {
      return level === 'urgent' ? '紧急' : (level === 'important' ? '重要' : '普通')
    },
    // 统一待办：角标统计 + 跳转
    loadTodo() {
      getTodoStats().then(res => {
        this.todoTotal = Number((res && res.total) || 0)
      }).catch(() => {})
    },
    goTodo() {
      this.$router.push('/todo/list')
    },
    logout() {
      this.$confirm('确定注销并退出系统吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.$store.dispatch('LogOut').then(() => {
          location.href = '/index'
        })
      }).catch(() => {})
    }
  },
  created() {
    this.loadUnread()
    this.loadTodo()
    // 每 60s 轮询未读数与待办
    this.noticeTimer = setInterval(() => {
      this.loadUnread()
    }, 60000)
    this.todoTimer = setInterval(() => {
      this.loadTodo()
    }, 60000)
  },
  beforeDestroy() {
    if (this.noticeTimer) {
      clearInterval(this.noticeTimer)
      this.noticeTimer = null
    }
    if (this.todoTimer) {
      clearInterval(this.todoTimer)
      this.todoTimer = null
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar.nav3 {
  .hamburger-container {
    display: none !important;
  }
}

.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: #0B2A6B;
  box-shadow: 0 1px 4px rgba(4, 20, 69, 0.2);
  display: flex;
  align-items: center;
  box-sizing: border-box;

  .navbar-brand {
    display: flex;
    align-items: center;
    flex-shrink: 0;
    padding-left: 16px;
    padding-right: 10px;
    height: 100%;
    border-right: 1px solid rgba(255, 255, 255, 0.12);

    .navbar-logo {
      width: 28px;
      height: 28px;
      border-radius: 6px;
      margin-right: 8px;
    }

    .navbar-title {
      color: #fff;
      font-size: 16px;
      font-weight: 600;
      letter-spacing: 0.5px;
      white-space: nowrap;
    }
  }

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    cursor: pointer;
    transition: background .3s;
    -webkit-tap-highlight-color:transparent;
    display: flex;
    align-items: center;
    flex-shrink: 0;
    margin-right: 8px;
    color: rgba(255, 255, 255, 0.85);

    &:hover {
      background: rgba(255, 255, 255, 0.08);
    }
  }

  .breadcrumb-container {
    flex-shrink: 0;
    ::v-deep .el-breadcrumb__inner {
      color: rgba(255, 255, 255, 0.85);
    }
    ::v-deep .el-breadcrumb__item:last-child .el-breadcrumb__inner {
      color: #fff;
    }
    ::v-deep .el-breadcrumb__separator {
      color: rgba(255, 255, 255, 0.4);
    }
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .topbar-container {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    margin-left: 8px;
  }

  .right-menu {
    height: 100%;
    line-height: 50px;
    display: flex;
    align-items: center;
    margin-left: auto;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-block;
      padding: 0 8px;
      height: 100%;
      font-size: 18px;
      color: rgba(255, 255, 255, 0.85);
      vertical-align: text-bottom;

      &.hover-effect {
        cursor: pointer;
        transition: background .3s;

        &:hover {
          background: rgba(255, 255, 255, 0.12);
        }
      }
    }

    /* 实时状态呼吸点（V1.2 签名元素：监控系统"在线心跳"语义） */
    .live-status {
      display: flex;
      align-items: center;
      font-size: 13px;
      color: rgba(255, 255, 255, 0.9);
      padding: 0 10px;
      cursor: default;

      .live-dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: #10B981;
        margin-right: 7px;
        position: relative;
        animation: livePulse 2s ease-in-out infinite;

        &::after {
          content: "";
          position: absolute;
          left: -4px;
          top: -4px;
          width: 16px;
          height: 16px;
          border-radius: 50%;
          border: 1px solid rgba(16, 185, 129, 0.5);
          animation: liveRing 2s ease-out infinite;
        }
      }
    }

    .help-btn {
      display: flex;
      align-items: center;
      font-size: 14px;
      color: rgba(255, 255, 255, 0.85);

      i {
        font-size: 16px;
        margin-right: 4px;
      }
    }

    .notice-btn {
      display: flex;
      align-items: center;
      font-size: 17px;
      color: rgba(255, 255, 255, 0.9);

      .notice-badge {
        ::v-deep .el-badge__content {
          border: none;
          transform: translateY(-2px) translateX(2px);
        }
      }
    }

    .account-container {
      margin-right: 0px;
      padding-right: 0px;

      .account-wrapper {
        display: flex;
        align-items: center;
        height: 100%;
        padding: 0 8px 0 12px;
        cursor: pointer;
        outline: none;

        .account-name {
          font-size: 14px;
          font-weight: 600;
          color: #fff;
        }

        .account-caret {
          font-size: 12px;
          color: rgba(255, 255, 255, 0.6);
          margin-left: 3px;
        }
      }
    }
  }
}

@keyframes livePulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

@keyframes liveRing {
  0% { transform: scale(0.6); opacity: 0.9; }
  100% { transform: scale(1.6); opacity: 0; }
}

/* 通知中心样式 */
.notice-center {
  max-height: 420px;
  overflow-y: auto;

  .notice-empty {
    text-align: center;
    padding: 40px 0;
    color: #9CA3AF;

    i {
      font-size: 42px;
    }

    p {
      margin-top: 10px;
      font-size: 14px;
    }
  }

  .notice-item {
    padding: 12px 14px;
    border-radius: 8px;
    cursor: pointer;
    transition: background .2s;
    border: 1px solid transparent;

    &:hover {
      background: #EAF0FB;
    }

    &.notice-item-unread {
      background: #F5F8FF;
      border-color: #D6E2F9;
    }

    .notice-item-head {
      display: flex;
      align-items: center;

      .notice-top {
        background: #205CF5;
        color: #fff;
        font-size: 12px;
        padding: 1px 6px;
        border-radius: 4px;
        margin-right: 6px;
      }

      .notice-item-title {
        flex: 1;
        min-width: 0;
        margin-left: 6px;
        font-size: 14px;
        font-weight: 600;
        color: #1F2937;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .notice-dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: #EF4444;
        flex-shrink: 0;
        margin-left: 6px;
      }
    }

    .notice-item-meta {
      margin-top: 6px;
      font-size: 12px;
      color: #6B7280;
    }
  }
}

.notice-detail {
  .notice-detail-title {
    font-size: 16px;
    font-weight: 700;
    color: #1F2937;
  }

  .notice-detail-meta {
    margin-top: 6px;
    font-size: 12px;
    color: #6B7280;
  }

  .notice-detail-content {
    margin-top: 14px;
    font-size: 14px;
    line-height: 1.9;
    color: #374151;
    white-space: pre-wrap;
    border-top: 1px solid #E5E7EB;
    padding-top: 14px;
  }
}
</style>
