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
      data: {
        device: { total: 0, online: 0, offline: 0, lineAbnormal: 0, onlineRate: 0, byType: [] },
        event: { today: 0, critical: 0, warning: 0, info: 0, trend: [], topDevices: [] },
        order: { pending: 0, dispatched: 0, repairing: 0, completed: 0, total: 0 },
        gateway: { total: 0, online: 0, offline: 0 },
        spare: { partTypes: 0, totalQuantity: 0, lowStock: 0 }
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
      getDashboardOverview().then(data => {
        this.data = Object.assign({}, this.data, data)
        this.$nextTick(() => {
          this.renderTypeChart()
          this.renderTrendChart()
          this.renderStatusChart()
        })
      }).catch(() => { /* 轮询失败静默 */ })
      listEvent({ pageNum: 1, pageSize: 8 }).then(res => {
        this.recentEvents = (res && res.rows) || []
      }).catch(() => { /* noop */ })
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
  grid-template-columns: repeat(4, 1fr);
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
  grid-template-columns: repeat(4, 1fr);
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
</style>
