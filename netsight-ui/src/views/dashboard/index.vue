<template>
  <div class="screen-wrap">
    <div class="screen" ref="screen" :class="{ 'is-full': isFull }">
      <!-- 顶部 -->
      <header class="screen-header">
        <div class="header-side header-left"></div>
        <div class="header-title">
          <h1>NetSight 运维监控大屏</h1>
          <div class="header-sub">网络资产 · 视频监控 · 告警联动 统一运维监控</div>
        </div>
        <div class="header-side header-right">
          <span class="header-time">{{ now }}</span>
          <el-button size="mini" plain class="fullscreen-btn" @click="toggleDemo">
            <i :class="demoMode ? 'el-icon-data-line' : 'el-icon-odometer'"></i>
            {{ demoMode ? '演示模式(100条)' : '真实数据' }}
          </el-button>
          <el-button size="mini" plain class="fullscreen-btn" @click="toggleFullscreen">
            <i :class="isFull ? 'el-icon-close' : 'el-icon-full-screen'"></i>
            {{ isFull ? '退出全屏' : '全屏' }}
          </el-button>
        </div>
      </header>

      <main class="screen-main">
        <!-- 左列 -->
        <section class="col">
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>设备类型分布</div>
            <div ref="typeChart" class="chart chart-md"></div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>边缘网关状态</div>
            <div class="gateway-grid">
              <div class="gw-item"><div class="gw-num" style="color:#36cfc9;">{{ data.gateway.online }}</div><div class="gw-label">在线网关</div></div>
              <div class="gw-item"><div class="gw-num" style="color:#ff7875;">{{ data.gateway.offline }}</div><div class="gw-label">离线网关</div></div>
              <div class="gw-item"><div class="gw-num">{{ data.gateway.total }}</div><div class="gw-label">网关总数</div></div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>备件库存概览</div>
            <div class="spare-grid">
              <div class="sp-item"><span class="sp-key">备件种类</span><span class="sp-val">{{ data.spare.partTypes }}</span></div>
              <div class="sp-item"><span class="sp-key">库存总量</span><span class="sp-val">{{ data.spare.totalQuantity }}</span></div>
              <div class="sp-item"><span class="sp-key">低库存</span><span class="sp-val" :style="{ color: data.spare.lowStock > 0 ? '#ffc53d' : '#52c41a' }">{{ data.spare.lowStock }}</span></div>
            </div>
            <div v-if="data.spare.lowStockList.length" class="mini-list">
              <div v-for="p in data.spare.lowStockList" :key="p.partNo" class="mini-item">
                <span class="mini-name" :title="p.partName">{{ p.partName }}</span>
                <span class="mini-val warn">{{ p.quantity }} / {{ p.safeStock }}</span>
              </div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>知识库与公告</div>
            <div class="kb-top">
              <div class="kb-num">{{ data.knowledge.total }}</div>
              <div class="kb-label">故障知识文章</div>
            </div>
            <div v-if="data.knowledge.byCategory.length" class="kb-tags">
              <span v-for="c in data.knowledge.byCategory" :key="c.category" class="kb-tag">{{ c.category }} {{ c.count }}</span>
            </div>
            <div v-if="data.notice.latest.length" class="mini-list">
              <div v-for="(n, ni) in data.notice.latest" :key="ni" class="mini-item">
                <span class="mini-name" :title="n.title">
                  <i v-if="n.isTop === 1" class="el-icon-top" style="color:#faad14;margin-right:2px;"></i>{{ n.title }}
                </span>
                <span class="mini-time">{{ n.publishTime }}</span>
              </div>
            </div>
            <el-empty v-if="!data.knowledge.total && !data.notice.latest.length" :image-size="40" description="暂无知识/公告" style="padding:6px 0;" />
          </div>
        </section>

        <!-- 中列 -->
        <section class="col col-center">
          <div class="metric-grid">
            <div class="metric">
              <div class="metric-label">设备总数</div>
              <div class="metric-num">{{ data.device.total }}</div>
            </div>
            <div class="metric">
              <div class="metric-label">在线设备</div>
              <div class="metric-num" style="color:#36cfc9;">{{ data.device.online }}</div>
            </div>
            <div class="metric">
              <div class="metric-label">离线设备</div>
              <div class="metric-num" style="color:#ff7875;">{{ data.device.offline }}</div>
            </div>
            <div class="metric">
              <div class="metric-label">在线率</div>
              <div class="metric-num" style="color:#40a9ff;">{{ data.device.onlineRate }}%</div>
            </div>
            <div class="metric">
              <div class="metric-label">今日告警</div>
              <div class="metric-num" style="color:#ffc53d;">{{ data.event.today }}</div>
            </div>
            <div class="metric">
              <div class="metric-label">低库存</div>
              <div class="metric-num" :style="{ color: data.spare.lowStock > 0 ? '#ff7875' : '#52c41a' }">{{ data.spare.lowStock }}</div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>近 7 日告警趋势</div>
            <div ref="trendChart" class="chart chart-md"></div>
          </div>
          <div class="panel panel-live">
            <div class="panel-title"><i class="dot"></i>实时告警<span class="live-tag" v-if="recentEvents.length">LIVE</span></div>
            <div class="live-list">
              <div v-for="(ev, i) in recentEvents" :key="ev.id" class="live-item" :class="'lv-' + (i % 2)">
                <span class="lv-tag" :class="sevClass(ev.severity)">{{ sevName(ev.severity) }}</span>
                <span class="lv-dev">{{ ev.deviceName }}</span>
                <span class="lv-type">{{ evTypeName(ev.eventType) }}</span>
                <span class="lv-time">{{ fmtTime(ev.createTime) }}</span>
              </div>
              <el-empty v-if="!recentEvents.length" :image-size="50" description="暂无告警" style="padding:10px 0;" />
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>点检执行情况</div>
            <div class="insp-row">
              <div class="insp-ring" ref="inspChart"></div>
              <div class="insp-info">
                <div class="insp-item"><span class="insp-key">计划总数</span><span class="insp-val">{{ data.inspection.plans }}</span></div>
                <div class="insp-item"><span class="insp-key">今日任务</span><span class="insp-val">{{ data.inspection.todayTasks }}</span></div>
                <div class="insp-item"><span class="insp-key">今日完成</span><span class="insp-val" style="color:#36cfc9;">{{ data.inspection.todayDone }}</span></div>
              </div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>近 7 日工单新增</div>
            <div ref="orderTrendChart" class="chart chart-sm"></div>
          </div>
        </section>

        <!-- 右列 -->
        <section class="col">
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>设备状态占比</div>
            <div ref="statusChart" class="chart chart-md"></div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>工单看板</div>
            <div class="order-grid">
              <div class="od-item"><div class="od-num" style="color:#ffc53d;">{{ data.order.pending }}</div><div class="od-label">待处理</div></div>
              <div class="od-item"><div class="od-num" style="color:#40a9ff;">{{ data.order.dispatched }}</div><div class="od-label">已派单</div></div>
              <div class="od-item"><div class="od-num" style="color:#b37feb;">{{ data.order.repairing }}</div><div class="od-label">维修中</div></div>
              <div class="od-item"><div class="od-num" style="color:#52c41a;">{{ data.order.completed }}</div><div class="od-label">已完成</div></div>
              <div class="od-item"><div class="od-num" style="color:#36cfc9;">{{ data.order.recovered }}</div><div class="od-label">自动恢复</div></div>
              <div class="od-item"><div class="od-num" style="color:#8c8c8c;">{{ data.order.closed }}</div><div class="od-label">已关闭</div></div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>告警级别统计</div>
            <div class="sev-grid">
              <div class="sv-item sv-critical"><span>严重</span><b>{{ data.event.critical }}</b></div>
              <div class="sv-item sv-warning"><span>警告</span><b>{{ data.event.warning }}</b></div>
              <div class="sv-item sv-info"><span>提示</span><b>{{ data.event.info }}</b></div>
            </div>
            <div class="sev-total">累计告警：{{ data.event.critical + data.event.warning + data.event.info }} 条</div>
            <div v-if="data.event.topDevices.length" class="top-list">
              <div class="top-title">告警 TOP 设备</div>
              <div v-for="(td, ti) in data.event.topDevices" :key="ti" class="top-item">
                <span class="top-idx" :class="{ 'top-hot': ti < 3 }">{{ ti + 1 }}</span>
                <span class="top-name" :title="td.deviceName || td.deviceCode">{{ td.deviceName || td.deviceCode }}</span>
                <span class="top-bar"><i :style="{ width: topBarW(td) }"></i></span>
                <span class="top-cnt">{{ td.count }}</span>
              </div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>通知发送监控</div>
            <div class="notify-grid">
              <div class="nt-item"><span class="nt-key">今日发送</span><span class="nt-val">{{ data.notify.today }}</span></div>
              <div class="nt-item"><span class="nt-key">发送成功</span><span class="nt-val" style="color:#52c41a;">{{ data.notify.success }}</span></div>
              <div class="nt-item"><span class="nt-key">发送失败</span><span class="nt-val" :style="{ color: data.notify.failed > 0 ? '#ff7875' : '#52c41a' }">{{ data.notify.failed }}</span></div>
              <div class="nt-item"><span class="nt-key">成功率</span><span class="nt-val" style="color:#40a9ff;">{{ data.notify.successRate }}%</span></div>
            </div>
          </div>
          <div class="panel">
            <div class="panel-title"><i class="dot"></i>今日运营速览</div>
            <div class="ops-grid">
              <div class="op-item"><span class="op-key">维修人员</span><span class="op-val">{{ data.ops.repairers }}</span></div>
              <div class="op-item"><span class="op-key">在岗人数</span><span class="op-val" style="color:#36cfc9;">{{ data.ops.onDuty }}</span></div>
              <div class="op-item"><span class="op-key">租户数量</span><span class="op-val">{{ data.ops.tenants }}</span></div>
              <div class="op-item"><span class="op-key">平台用户</span><span class="op-val">{{ data.ops.users }}</span></div>
              <div class="op-item"><span class="op-key">今日出入库</span><span class="op-val" style="color:#40a9ff;">{{ data.ops.todayStock }}</span></div>
            </div>
          </div>
        </section>
      </main>

      <footer class="screen-footer">NetSight NAMS · 数据实时刷新中 · {{ now }}</footer>
    </div>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { getDashboardOverview } from '@/api/dashboard'
import { listEvent } from '@/api/alert/event'
import { connectWebSocket, onWebSocketMessage, disconnectWebSocket } from '@/utils/websocket'

const SEV_MAP = { critical: '严重', warning: '警告', info: '提示', resolved: '恢复' }
const TYPE_MAP = {
  device_offline: '设备离线',
  device_line_abnormal: '外线异常',
  device_recovered: '设备恢复',
  stock_low: '库存预警',
  manual: '手动通知'
}

export default {
  name: 'Dashboard',
  data() {
    return {
      now: this.fmtNow(),
      isFull: false,
      demoMode: localStorage.getItem('netsight-dashboard-demo') === '1',
      data: {
        device: { total: 0, online: 0, offline: 0, lineAbnormal: 0, onlineRate: 0, byType: [] },
        event: { today: 0, critical: 0, warning: 0, info: 0, trend: [], topDevices: [] },
        order: { pending: 0, dispatched: 0, repairing: 0, completed: 0, closed: 0, recovered: 0, total: 0, trend: [] },
        gateway: { total: 0, online: 0, offline: 0 },
        spare: { partTypes: 0, totalQuantity: 0, lowStock: 0, lowStockList: [] },
        knowledge: { total: 0, byCategory: [] },
        notice: { total: 0, latest: [] },
        inspection: { plans: 0, todayTasks: 0, todayDone: 0, doneRate: 0 },
        notify: { today: 0, success: 0, failed: 0, successRate: 0 },
        ops: { repairers: 0, onDuty: 0, tenants: 0, users: 0, todayStock: 0 }
      },
      recentEvents: [],
      charts: {},
      timer: null,
      clockTimer: null,
      wsOffs: []
    }
  },
  created() {
    this.refreshAll()
    this.clockTimer = setInterval(() => { this.now = this.fmtNow() }, 1000)
    this.timer = setInterval(() => { this.refreshAll() }, 30000)
    // WebSocket 实时：任一事件/设备/工单变更 → 全量刷新
    connectWebSocket()
    this.wsOffs.push(onWebSocketMessage('event', () => this.refreshAll()))
    this.wsOffs.push(onWebSocketMessage('device-status', () => this.refreshAll()))
    this.wsOffs.push(onWebSocketMessage('order', () => this.refreshAll()))
    window.addEventListener('resize', this.resizeCharts)
    document.addEventListener('fullscreenchange', this.onFullscreenChange)
  },
  beforeDestroy() {
    clearInterval(this.timer)
    clearInterval(this.clockTimer)
    window.removeEventListener('resize', this.resizeCharts)
    document.removeEventListener('fullscreenchange', this.onFullscreenChange)
    this.wsOffs.forEach(off => off())
    this.wsOffs = []
    Object.keys(this.charts).forEach(k => { try { this.charts[k].dispose() } catch (e) { /* noop */ } })
    this.charts = {}
  },
  methods: {
    fmtNow() {
      const d = new Date()
      const p = n => (n < 10 ? '0' + n : '' + n)
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    fmtTime(t) {
      if (!t) return ''
      const s = String(t).replace('T', ' ').replace(/-/g, '/')
      return s.length > 16 ? s.substring(5, 16) : s
    },
    sevName(s) { return SEV_MAP[s] || s || '未知' },
    sevClass(s) { return 'sev-' + (s || 'info') },
    evTypeName(t) { return TYPE_MAP[t] || t || '未知' },
    refreshAll() {
      getDashboardOverview(this.demoMode ? 1 : 0).then(data => {
        this.data = Object.assign({}, this.data, data)
        // 演示模式：实时告警列表直接用后端返回的 100 条模拟告警（需在异步回调内取，避免竞态）
        if (this.demoMode && data.recentEvents && data.recentEvents.length) {
          this.recentEvents = data.recentEvents
        } else if (!this.demoMode) {
          // 真实模式：走事件分页接口
          listEvent({ pageNum: 1, pageSize: 8 }).then(res => {
            this.recentEvents = (res && res.rows) || []
          }).catch(() => { /* noop */ })
        }
        this.$nextTick(() => {
          this.renderTypeChart()
          this.renderTrendChart()
          this.renderStatusChart()
          this.renderInspChart()
          this.renderOrderTrendChart()
        })
      }).catch(() => { /* 轮询失败静默 */ })
    },
    toggleDemo() {
      this.demoMode = !this.demoMode
      localStorage.setItem('netsight-dashboard-demo', this.demoMode ? '1' : '0')
      this.refreshAll()
    },
    initChart(refName) {
      if (!this.charts[refName] && this.$refs[refName]) {
        this.charts[refName] = echarts.init(this.$refs[refName])
      }
      return this.charts[refName]
    },
    renderTypeChart() {
      const c = this.initChart('typeChart')
      if (!c) return
      const byType = this.data.device.byType || []
      c.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 } },
        legend: { data: ['在线', '离线'], textStyle: { color: '#8fb8e8', fontSize: 11 }, top: 0, itemWidth: 12, itemHeight: 8 },
        grid: { left: 36, right: 12, top: 26, bottom: 20 },
        xAxis: { type: 'category', data: byType.map(t => this.typeName(t.deviceType)), axisLine: { lineStyle: { color: '#2a5a8f' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(64,158,255,0.15)' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        series: [
          { name: '在线', type: 'bar', stack: 't', barWidth: 22, data: byType.map(t => t.online), itemStyle: { color: '#36cfc9', borderRadius: [3, 3, 0, 0] } },
          { name: '离线', type: 'bar', stack: 't', barWidth: 22, data: byType.map(t => t.offline), itemStyle: { color: '#ff7875' } }
        ]
      })
    },
    renderTrendChart() {
      const c = this.initChart('trendChart')
      if (!c) return
      const trend = this.data.event.trend || []
      c.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 } },
        grid: { left: 36, right: 16, top: 20, bottom: 22 },
        xAxis: { type: 'category', data: trend.map(t => t.date), boundaryGap: false, axisLine: { lineStyle: { color: '#2a5a8f' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(64,158,255,0.15)' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        series: [{
          name: '告警数', type: 'line', smooth: true, data: trend.map(t => t.count),
          symbolSize: 5, lineStyle: { color: '#40a9ff', width: 2 },
          itemStyle: { color: '#40a9ff' },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(64,158,255,0.35)' }, { offset: 1, color: 'rgba(64,158,255,0.02)' }]) }
        }]
      })
    },
    renderStatusChart() {
      const c = this.initChart('statusChart')
      if (!c) return
      const d = this.data.device
      const data = [
        { value: d.online, name: '在线' },
        { value: d.lineAbnormal, name: '链路异常' },
        { value: d.offline, name: '离线' }
      ].filter(x => x.value > 0)
      c.setOption({
        backgroundColor: 'transparent',
        color: ['#36cfc9', '#909399', '#ff7875'],
        tooltip: { trigger: 'item', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 }, formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0, textStyle: { color: '#8fb8e8', fontSize: 10 }, itemWidth: 12, itemHeight: 8 },
        series: [{
          type: 'pie', radius: ['48%', '72%'], center: ['50%', '44%'],
          label: { show: false }, labelLine: { show: false },
          itemStyle: { borderColor: 'rgba(10,30,60,0.6)', borderWidth: 2 },
          data: data.length ? data : [{ value: 1, name: '暂无设备', itemStyle: { color: '#2a5a8f' } }]
        }]
      })
    },
    renderInspChart() {
      const c = this.initChart('inspChart')
      if (!c) return
      const done = this.data.inspection.doneRate || 0
      c.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'item', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 }, formatter: '{b}: {c}%' },
        series: [{
          type: 'gauge', radius: '98%', center: ['50%', '52%'], startAngle: 210, endAngle: -30,
          min: 0, max: 100, splitNumber: 5,
          progress: { show: true, width: 10, itemStyle: { color: '#36cfc9' } },
          axisLine: { lineStyle: { width: 10, color: [[1, 'rgba(64,158,255,0.18)']] } },
          axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
          anchor: { show: false },
          title: { show: true, offsetCenter: [0, '32%'], color: '#8fb8e8', fontSize: 10 },
          detail: { valueAnimation: true, offsetCenter: [0, '-2%'], color: '#36cfc9', fontSize: 18, fontWeight: 'bold', formatter: '{value}%' },
          data: [{ value: done, name: '完成率' }]
        }]
      })
    },
    renderOrderTrendChart() {
      const c = this.initChart('orderTrendChart')
      if (!c) return
      const trend = this.data.order.trend || []
      c.setOption({
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 } },
        grid: { left: 36, right: 16, top: 20, bottom: 22 },
        xAxis: { type: 'category', data: trend.map(t => t.date), boundaryGap: false, axisLine: { lineStyle: { color: '#2a5a8f' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(64,158,255,0.15)' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
        series: [{
          name: '新增工单', type: 'bar', barWidth: 14, data: trend.map(t => t.count),
          itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#36cfc9' }, { offset: 1, color: 'rgba(54,207,201,0.25)' }]), borderRadius: [3, 3, 0, 0] }
        }]
      })
    },
    topBarW(td) {
      const list = this.data.event.topDevices || []
      const max = Math.max.apply(null, list.map(x => x.count || 0))
      if (!max) return '0%'
      return Math.max(8, Math.round(((td.count || 0) / max) * 100)) + '%'
    },
    typeName(t) {
      return { network: '网络', camera: '视频', nvr: 'NVR', door_controller: '门禁' }[t] || t || '未知'
    },
    resizeCharts() {
      Object.keys(this.charts).forEach(k => { try { this.charts[k].resize() } catch (e) { /* noop */ } })
    },
    toggleFullscreen() {
      const el = this.$refs.screen
      if (!el) return
      if (!this.isFull) {
        // 全屏目标为大屏容器 .screen（而非 documentElement）：原生全屏只渲染该元素，
        // 浏览器自动隐藏侧边栏/顶栏/tags-view，无需 z-index 博弈
        if (el.requestFullscreen) el.requestFullscreen()
        else if (el.webkitRequestFullscreen) el.webkitRequestFullscreen()
        else if (el.msRequestFullscreen) el.msRequestFullscreen()
        this.isFull = true
      } else {
        if (document.exitFullscreen) document.exitFullscreen()
        else if (document.webkitExitFullscreen) document.webkitExitFullscreen()
        this.isFull = false
      }
      setTimeout(() => this.resizeCharts(), 200)
    },
    onFullscreenChange() {
      this.isFull = !!document.fullscreenElement
      setTimeout(() => this.resizeCharts(), 200)
    }
  }
}
</script>

<style scoped>
.screen-wrap {
  background: linear-gradient(160deg, #0b1f3a 0%, #123a63 55%, #0e2c50 100%);
  border-radius: 8px;
  padding: 14px;
  color: #e6f1ff;
  min-height: calc(100vh - 140px);
  box-sizing: border-box;
}
.screen.is-full {
  position: fixed;
  inset: 0;
  z-index: 3000;
  border-radius: 0;
  padding: 18px;
  display: flex;
  flex-direction: column;
  height: 100vh;
  box-sizing: border-box;
  overflow: hidden;
}
/* 全屏弹性布局：三列等高、面板均分剩余高度、图表自适应，任何屏幕比例都不溢出 */
.screen.is-full .screen-header { flex: none; }
.screen.is-full .screen-main {
  flex: 1;
  min-height: 0;
}
.screen.is-full .col {
  min-height: 0;
}
.screen.is-full .panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.screen.is-full .panel .chart-md {
  flex: 1;
  min-height: 0;
  height: auto;
}
.screen.is-full .panel-live .live-list {
  flex: 1;
  min-height: 0;
  max-height: none;
}
.screen.is-full .gateway-grid,
.screen.is-full .spare-grid,
.screen.is-full .order-grid,
.screen.is-full .sev-grid {
  flex: 1;
  align-content: center;
}
.screen.is-full .metric-grid {
  flex: none;
}
.screen.is-full .screen-footer { flex: none; }
.screen-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 6px 14px;
}
.header-title {
  text-align: center;
}
.header-title h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 6px;
  background: linear-gradient(90deg, #40a9ff, #36cfc9, #40a9ff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.header-sub {
  font-size: 12px;
  color: #6f9ed1;
  margin-top: 4px;
  letter-spacing: 2px;
}
.header-side {
  flex: 1;
}
.header-right {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
}
.header-time {
  font-size: 14px;
  color: #8fb8e8;
  font-family: Consolas, monospace;
}
.fullscreen-btn {
  color: #8fb8e8 !important;
  border-color: rgba(64, 158, 255, 0.5) !important;
  background: transparent !important;
}
.screen-main {
  display: flex;
  gap: 14px;
}
.col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.col-center {
  flex: 1.5;
}
/* 三列底部对齐：每列最后一个 panel（备件库存/实时告警/告警级别）占满剩余高度 */
.col > .panel:last-child {
  flex: 1;
}
.panel {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(64, 158, 255, 0.28);
  border-radius: 8px;
  padding: 12px;
  box-sizing: border-box;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #a8ccf2;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
}
.panel-title .dot {
  width: 6px;
  height: 6px;
  background: #40a9ff;
  border-radius: 50%;
  margin-right: 7px;
  box-shadow: 0 0 6px #40a9ff;
}
.live-tag {
  margin-left: 8px;
  font-size: 10px;
  color: #ff7875;
  border: 1px solid rgba(255, 120, 117, 0.6);
  border-radius: 3px;
  padding: 0 4px;
  animation: blink 1.5s infinite;
}
@keyframes blink { 50% { opacity: 0.3; } }
.chart {
  width: 100%;
}
.chart-md {
  height: 200px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 14px;
}
.metric {
  background: linear-gradient(135deg, rgba(64, 158, 255, 0.22), rgba(64, 158, 255, 0.08));
  border: 1px solid rgba(64, 158, 255, 0.4);
  border-radius: 8px;
  padding: 14px 10px;
  text-align: center;
}
.metric-label {
  font-size: 12px;
  color: #8fb8e8;
}
.metric-num {
  font-size: 30px;
  font-weight: 700;
  color: #fff;
  margin-top: 4px;
  font-family: Consolas, monospace;
}
.live-list {
  max-height: 210px;
  overflow-y: auto;
}
.live-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  font-size: 12px;
  border-radius: 4px;
}
.live-item.lv-1 { background: rgba(255, 255, 255, 0.03); }
.lv-tag {
  flex: none;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  color: #fff;
}
.sev-critical { background: #ff4d4f; }
.sev-warning { background: #faad14; }
.sev-info { background: #409eff; }
.sev-resolved { background: #52c41a; }
.lv-dev { color: #e6f1ff; font-weight: 500; }
.lv-type { color: #6f9ed1; }
.lv-time { margin-left: auto; color: #5b83b5; font-size: 11px; }
.gateway-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  text-align: center;
}
.gw-num {
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  font-family: Consolas, monospace;
}
.gw-label {
  font-size: 11px;
  color: #6f9ed1;
  margin-top: 2px;
}
.spare-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sp-item {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  border-bottom: 1px dashed rgba(64, 158, 255, 0.2);
  padding-bottom: 6px;
}
.sp-key { color: #6f9ed1; }
.sp-val { color: #fff; font-weight: 600; font-family: Consolas, monospace; }
.order-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  text-align: center;
}
.od-num {
  font-size: 22px;
  font-weight: 700;
  font-family: Consolas, monospace;
}
.od-label {
  font-size: 11px;
  color: #6f9ed1;
  margin-top: 2px;
}
.sev-grid {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.sv-item {
  flex: 1;
  text-align: center;
  padding: 10px 4px;
  border-radius: 6px;
  font-size: 12px;
  color: #e6f1ff;
}
.sv-item b {
  display: block;
  font-size: 20px;
  margin-top: 2px;
  font-family: Consolas, monospace;
}
.sv-critical { background: rgba(255, 77, 79, 0.18); border: 1px solid rgba(255, 77, 79, 0.4); }
.sv-warning { background: rgba(250, 173, 20, 0.15); border: 1px solid rgba(250, 173, 20, 0.4); }
.sv-info { background: rgba(64, 158, 255, 0.15); border: 1px solid rgba(64, 158, 255, 0.4); }
.sev-total {
  font-size: 11px;
  color: #6f9ed1;
  text-align: right;
}
.screen-footer {
  text-align: center;
  font-size: 11px;
  color: #4a729f;
  padding-top: 12px;
  letter-spacing: 1px;
}
/* ===== 新增板块样式（驾驶舱丰富化 V1.5） ===== */
.mini-list {
  margin-top: 8px;
  max-height: 120px;
  overflow-y: auto;
}
.mini-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 2px;
  font-size: 12px;
  border-bottom: 1px dashed rgba(64, 158, 255, 0.15);
}
.mini-name {
  flex: 1;
  color: #e6f1ff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mini-val {
  color: #fff;
  font-family: Consolas, monospace;
  font-weight: 600;
}
.mini-val.warn { color: #ffc53d; }
.mini-time {
  flex: none;
  color: #5b83b5;
  font-size: 11px;
  font-family: Consolas, monospace;
}
.kb-top {
  text-align: center;
  padding: 10px 0 4px;
}
.kb-num {
  font-size: 32px;
  font-weight: 700;
  color: #36cfc9;
  font-family: Consolas, monospace;
  line-height: 1.1;
}
.kb-label { font-size: 12px; color: #6f9ed1; }
.kb-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.kb-tag {
  font-size: 11px;
  color: #8fb8e8;
  background: rgba(64, 158, 255, 0.12);
  border: 1px solid rgba(64, 158, 255, 0.35);
  border-radius: 4px;
  padding: 1px 7px;
}
.insp-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.insp-ring {
  width: 108px;
  height: 108px;
  flex: none;
}
.insp-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.insp-item {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.insp-key { color: #6f9ed1; }
.insp-val { color: #fff; font-weight: 600; font-family: Consolas, monospace; }
.chart-sm { height: 120px; }
.top-list {
  margin-top: 10px;
  border-top: 1px dashed rgba(64, 158, 255, 0.2);
  padding-top: 8px;
}
.top-title {
  font-size: 11px;
  color: #6f9ed1;
  margin-bottom: 6px;
}
.top-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 0;
  font-size: 12px;
}
.top-idx {
  flex: none;
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.08);
  color: #8fb8e8;
  font-family: Consolas, monospace;
  font-size: 11px;
}
.top-idx.top-hot {
  background: rgba(255, 77, 79, 0.25);
  color: #ff7875;
}
.top-name {
  flex: none;
  max-width: 120px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #e6f1ff;
}
.top-bar {
  flex: 1;
  height: 6px;
  background: rgba(64, 158, 255, 0.15);
  border-radius: 3px;
  overflow: hidden;
}
.top-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #409eff, #36cfc9);
  border-radius: 3px;
}
.top-cnt {
  flex: none;
  color: #fff;
  font-family: Consolas, monospace;
  font-weight: 600;
  min-width: 24px;
  text-align: right;
}
.notify-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.nt-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(64, 158, 255, 0.1);
  border: 1px solid rgba(64, 158, 255, 0.25);
  border-radius: 6px;
  padding: 9px 10px;
  font-size: 12px;
}
.nt-key { color: #6f9ed1; }
.nt-val { color: #fff; font-weight: 700; font-family: Consolas, monospace; font-size: 18px; }
.ops-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.op-item {
  text-align: center;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(64, 158, 255, 0.2);
  border-radius: 6px;
  padding: 8px 4px;
}
.op-key { display: block; font-size: 11px; color: #6f9ed1; }
.op-val { display: block; font-size: 20px; font-weight: 700; color: #fff; margin-top: 2px; font-family: Consolas, monospace; }
</style>
