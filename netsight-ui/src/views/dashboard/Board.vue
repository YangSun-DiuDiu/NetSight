<template>
  <div class="board" :class="'board-' + boardKey">
    <!-- 核心指标（6 数字） -->
    <template v-if="boardKey === 'metric_summary'">
      <div class="metric-grid wall-metric">
        <div class="metric"><div class="metric-label">设备总数</div><div class="metric-num">{{ data.device.total }}</div></div>
        <div class="metric"><div class="metric-label">在线设备</div><div class="metric-num" style="color:#36cfc9;">{{ data.device.online }}</div></div>
        <div class="metric"><div class="metric-label">离线设备</div><div class="metric-num" style="color:#ff7875;">{{ data.device.offline }}</div></div>
        <div class="metric"><div class="metric-label">在线率</div><div class="metric-num" style="color:#40a9ff;">{{ data.device.onlineRate }}%</div></div>
        <div class="metric"><div class="metric-label">今日告警</div><div class="metric-num" style="color:#ffc53d;">{{ data.event.today }}</div></div>
        <div class="metric"><div class="metric-label">低库存</div><div class="metric-num" :style="{ color: data.spare.lowStock > 0 ? '#ff7875' : '#52c41a' }">{{ data.spare.lowStock }}</div></div>
      </div>
    </template>

    <!-- 设备类型分布 -->
    <template v-else-if="boardKey === 'type_chart'">
      <div ref="chartEl" class="chart chart-fill"></div>
    </template>

    <!-- 设备状态占比 -->
    <template v-else-if="boardKey === 'status_chart'">
      <div ref="chartEl" class="chart chart-fill"></div>
    </template>

    <!-- 边缘网关状态 -->
    <template v-else-if="boardKey === 'gateway'">
      <div class="gateway-grid wall-gw">
        <div class="gw-item"><div class="gw-num" style="color:#36cfc9;">{{ data.gateway.online }}</div><div class="gw-label">在线网关</div></div>
        <div class="gw-item"><div class="gw-num" style="color:#ff7875;">{{ data.gateway.offline }}</div><div class="gw-label">离线网关</div></div>
        <div class="gw-item"><div class="gw-num">{{ data.gateway.total }}</div><div class="gw-label">网关总数</div></div>
      </div>
      <div class="wall-tip">网关在线状态基于心跳实时判定</div>
    </template>

    <!-- 备件库存概览 -->
    <template v-else-if="boardKey === 'spare'">
      <div class="spare-grid wall-spare">
        <div class="sp-item"><span class="sp-key">备件种类</span><span class="sp-val">{{ data.spare.partTypes }}</span></div>
        <div class="sp-item"><span class="sp-key">库存总量</span><span class="sp-val">{{ data.spare.totalQuantity }}</span></div>
        <div class="sp-item"><span class="sp-key">低库存</span><span class="sp-val" :style="{ color: data.spare.lowStock > 0 ? '#ffc53d' : '#52c41a' }">{{ data.spare.lowStock }}</span></div>
      </div>
      <div v-if="data.spare.lowStockList.length" class="mini-list wall-mini">
        <div v-for="p in data.spare.lowStockList" :key="p.partNo" class="mini-item">
          <span class="mini-name" :title="p.partName">{{ p.partName }}</span>
          <span class="mini-val warn">{{ p.quantity }} / {{ p.safeStock }}</span>
        </div>
      </div>
    </template>

    <!-- 知识库与公告 -->
    <template v-else-if="boardKey === 'knowledge'">
      <div class="kb-top"><div class="kb-num">{{ data.knowledge.total }}</div><div class="kb-label">故障知识文章</div></div>
      <div v-if="data.knowledge.byCategory.length" class="kb-tags wall-kb-tags">
        <span v-for="c in data.knowledge.byCategory" :key="c.category" class="kb-tag">{{ c.category }} {{ c.count }}</span>
      </div>
      <div v-if="data.notice.latest.length" class="mini-list wall-mini">
        <div v-for="(n, ni) in data.notice.latest" :key="ni" class="mini-item">
          <span class="mini-name" :title="n.title"><i v-if="n.isTop === 1" class="el-icon-top" style="color:#faad14;margin-right:2px;"></i>{{ n.title }}</span>
          <span class="mini-time">{{ n.publishTime }}</span>
        </div>
      </div>
      <el-empty v-if="!data.knowledge.total && !data.notice.latest.length" :image-size="40" description="暂无知识/公告" style="padding:6px 0;" />
    </template>

    <!-- 近 7 日告警趋势 -->
    <template v-else-if="boardKey === 'trend_chart'">
      <div ref="chartEl" class="chart chart-fill"></div>
    </template>

    <!-- 实时告警 -->
    <template v-else-if="boardKey === 'live_alert'">
      <div class="live-list wall-live">
        <div v-for="(ev, i) in recentEvents" :key="ev.id" class="live-item" :class="'lv-' + (i % 2)">
          <span class="lv-tag" :class="sevClass(ev.severity)">{{ sevName(ev.severity) }}</span>
          <span class="lv-dev">{{ ev.deviceName }}</span>
          <span class="lv-type">{{ evTypeName(ev.eventType) }}</span>
          <span class="lv-time">{{ fmtTime(ev.createTime) }}</span>
        </div>
        <el-empty v-if="!recentEvents.length" :image-size="40" description="暂无告警" style="padding:10px 0;" />
      </div>
    </template>

    <!-- 点检执行情况 -->
    <template v-else-if="boardKey === 'inspection'">
      <div class="insp-stats wall-insp">
        <div class="insp-item"><span class="insp-key">计划总数</span><span class="insp-val">{{ data.inspection.plans }}</span></div>
        <div class="insp-item"><span class="insp-key">今日任务</span><span class="insp-val">{{ data.inspection.todayTasks }}</span></div>
        <div class="insp-item"><span class="insp-key">今日完成</span><span class="insp-val" style="color:#36cfc9;">{{ data.inspection.todayDone }}</span></div>
        <div class="insp-item"><span class="insp-key">完成率</span><span class="insp-val" style="color:#40a9ff;">{{ data.inspection.doneRate }}%</span></div>
      </div>
      <div ref="chartEl" class="chart chart-fill wall-insp-chart"></div>
    </template>

    <!-- 近 7 日工单新增 -->
    <template v-else-if="boardKey === 'order_trend'">
      <div ref="chartEl" class="chart chart-fill"></div>
    </template>

    <!-- 工单看板 -->
    <template v-else-if="boardKey === 'order_board'">
      <div class="order-grid wall-order">
        <div class="od-item"><div class="od-num" style="color:#ffc53d;">{{ data.order.pending }}</div><div class="od-label">待处理</div></div>
        <div class="od-item"><div class="od-num" style="color:#40a9ff;">{{ data.order.dispatched }}</div><div class="od-label">已派单</div></div>
        <div class="od-item"><div class="od-num" style="color:#b37feb;">{{ data.order.repairing }}</div><div class="od-label">维修中</div></div>
        <div class="od-item"><div class="od-num" style="color:#52c41a;">{{ data.order.completed }}</div><div class="od-label">已完成</div></div>
        <div class="od-item"><div class="od-num" style="color:#36cfc9;">{{ data.order.recovered }}</div><div class="od-label">自动恢复</div></div>
        <div class="od-item"><div class="od-num" style="color:#8c8c8c;">{{ data.order.closed }}</div><div class="od-label">已关闭</div></div>
      </div>
    </template>

    <!-- 告警级别统计 -->
    <template v-else-if="boardKey === 'alert_level'">
      <div class="sev-grid wall-sev">
        <div class="sv-item sv-critical"><span>严重</span><b>{{ data.event.critical }}</b></div>
        <div class="sv-item sv-warning"><span>警告</span><b>{{ data.event.warning }}</b></div>
        <div class="sv-item sv-info"><span>提示</span><b>{{ data.event.info }}</b></div>
      </div>
      <div v-if="data.event.topDevices.length" class="top-list wall-top">
        <div v-for="(td, ti) in data.event.topDevices" :key="ti" class="top-item">
          <span class="top-idx" :class="{ 'top-hot': ti < 3 }">{{ ti + 1 }}</span>
          <span class="top-name" :title="td.deviceName || td.deviceCode">{{ td.deviceName || td.deviceCode }}</span>
          <span class="top-bar"><i :style="{ width: topBarW(td) }"></i></span>
          <span class="top-cnt">{{ td.count }}</span>
        </div>
      </div>
    </template>

    <!-- 通知发送监控 -->
    <template v-else-if="boardKey === 'notify'">
      <div class="notify-grid wall-notify">
        <div class="nt-item"><span class="nt-key">今日发送</span><span class="nt-val">{{ data.notify.today }}</span></div>
        <div class="nt-item"><span class="nt-key">发送成功</span><span class="nt-val" style="color:#52c41a;">{{ data.notify.success }}</span></div>
        <div class="nt-item"><span class="nt-key">发送失败</span><span class="nt-val" :style="{ color: data.notify.failed > 0 ? '#ff7875' : '#52c41a' }">{{ data.notify.failed }}</span></div>
        <div class="nt-item"><span class="nt-key">成功率</span><span class="nt-val" style="color:#40a9ff;">{{ data.notify.successRate }}%</span></div>
      </div>
    </template>

    <!-- 今日运营速览 -->
    <template v-else-if="boardKey === 'ops'">
      <div class="ops-grid wall-ops">
        <div class="op-item"><span class="op-key">维修人员</span><span class="op-val">{{ data.ops.repairers }}</span></div>
        <div class="op-item"><span class="op-key">在岗人数</span><span class="op-val" style="color:#36cfc9;">{{ data.ops.onDuty }}</span></div>
        <div class="op-item"><span class="op-key">租户数量</span><span class="op-val">{{ data.ops.tenants }}</span></div>
        <div class="op-item"><span class="op-key">平台用户</span><span class="op-val">{{ data.ops.users }}</span></div>
        <div class="op-item"><span class="op-key">今日出入库</span><span class="op-val" style="color:#40a9ff;">{{ data.ops.todayStock }}</span></div>
      </div>
    </template>

    <!-- 未知板块兜底 -->
    <template v-else>
      <el-empty :image-size="40" description="未知板块" style="padding:20px 0;" />
    </template>
  </div>
</template>

<script>
import * as echarts from 'echarts'

const SEV_MAP = { critical: '严重', warning: '警告', info: '提示', resolved: '恢复' }
const TYPE_MAP = {
  device_offline: '设备离线',
  device_line_abnormal: '外线异常',
  device_recovered: '设备恢复',
  stock_low: '库存预警',
  manual: '手动通知'
}

/** 上墙板块组件：按 boardKey 渲染单个板块内容，图表自管理（init/resize/刷新） */
export default {
  name: 'DashboardBoard',
  props: {
    boardKey: { type: String, required: true },
    data: { type: Object, required: true },
    recentEvents: { type: Array, default: () => [] }
  },
  data() {
    return { chart: null, resizeTimer: null }
  },
  computed: {
    isChartBoard() {
      return ['type_chart', 'status_chart', 'trend_chart', 'inspection', 'order_trend'].includes(this.boardKey)
    }
  },
  mounted() {
    if (this.isChartBoard) {
      this.initChart()
      this.renderChart()
      window.addEventListener('resize', this.onResize)
    }
  },
  beforeDestroy() {
    if (this.resizeTimer) clearTimeout(this.resizeTimer)
    window.removeEventListener('resize', this.onResize)
    if (this.chart) { try { this.chart.dispose() } catch (e) { /* noop */ } this.chart = null }
  },
  watch: {
    data: {
      deep: true,
      handler() { if (this.isChartBoard) this.renderChart() }
    }
  },
  methods: {
    initChart() {
      if (!this.chart && this.$refs.chartEl) {
        this.chart = echarts.init(this.$refs.chartEl)
      }
    },
    onResize() {
      clearTimeout(this.resizeTimer)
      this.resizeTimer = setTimeout(() => { if (this.chart) this.chart.resize() }, 100)
    },
    renderChart() {
      if (!this.chart) { this.initChart(); if (!this.chart) return }
      const key = this.boardKey
      if (key === 'type_chart') {
        const byType = this.data.device.byType || []
        this.chart.setOption({
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
      } else if (key === 'status_chart') {
        const d = this.data.device
        const chartData = [
          { value: d.online, name: '在线' },
          { value: d.lineAbnormal, name: '链路异常' },
          { value: d.offline, name: '离线' }
        ].filter(x => x.value > 0)
        this.chart.setOption({
          backgroundColor: 'transparent',
          color: ['#36cfc9', '#909399', '#ff7875'],
          tooltip: { trigger: 'item', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 }, formatter: '{b}: {c} ({d}%)' },
          legend: { bottom: 0, textStyle: { color: '#8fb8e8', fontSize: 10 }, itemWidth: 12, itemHeight: 8 },
          series: [{
            type: 'pie', radius: ['48%', '72%'], center: ['50%', '44%'],
            label: { show: false }, labelLine: { show: false },
            itemStyle: { borderColor: 'rgba(10,30,60,0.6)', borderWidth: 2 },
            data: chartData.length ? chartData : [{ value: 1, name: '暂无设备', itemStyle: { color: '#2a5a8f' } }]
          }]
        })
      } else if (key === 'trend_chart') {
        const trend = this.data.event.trend || []
        this.chart.setOption({
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
      } else if (key === 'inspection') {
        const trend = this.data.inspection.weekTrend || []
        if (!trend.length) { this.chart.clear(); return }
        this.chart.setOption({
          backgroundColor: 'transparent',
          tooltip: { trigger: 'axis', backgroundColor: 'rgba(10,30,60,0.9)', borderColor: '#409eff', textStyle: { color: '#fff', fontSize: 11 } },
          legend: { data: ['任务数', '完成数'], textStyle: { color: '#8fb8e8', fontSize: 11 }, top: 0, itemWidth: 12, itemHeight: 8 },
          grid: { left: 34, right: 10, top: 24, bottom: 18 },
          xAxis: { type: 'category', data: trend.map(t => t.date), axisLine: { lineStyle: { color: '#2a5a8f' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
          yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(64,158,255,0.15)' } }, axisLabel: { color: '#8fb8e8', fontSize: 10 } },
          series: [
            { name: '任务数', type: 'bar', barWidth: 9, barGap: '30%', itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#40a9ff' }, { offset: 1, color: 'rgba(64,169,255,0.25)' }]), borderRadius: [2, 2, 0, 0] }, data: trend.map(t => t.tasks) },
            { name: '完成数', type: 'bar', barWidth: 9, itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#36cfc9' }, { offset: 1, color: 'rgba(54,207,201,0.25)' }]), borderRadius: [2, 2, 0, 0] }, data: trend.map(t => t.done) }
          ]
        })
      } else if (key === 'order_trend') {
        const trend = this.data.order.trend || []
        this.chart.setOption({
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
      }
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
    sevName(s) { return SEV_MAP[s] || s || '未知' },
    sevClass(s) { return 'sev-' + (s || 'info') },
    evTypeName(t) { return TYPE_MAP[t] || t || '未知' },
    fmtTime(t) {
      if (!t) return ''
      const s = String(t).replace('T', ' ').replace(/-/g, '/')
      return s.length > 16 ? s.substring(5, 16) : s
    }
  }
}
</script>

<style scoped>
.board {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
}
/* 图表板块占满 */
.chart-fill {
  flex: 1;
  min-height: 0;
  width: 100%;
}
/* 核心指标：上墙格子内 3×2 紧凑布局 */
.wall-metric {
  flex: 1;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 0;
  align-content: center;
}
.wall-metric .metric { padding: 12px 8px; }
.wall-metric .metric-num { font-size: 26px; }
.wall-gw {
  flex: 1;
  align-content: center;
}
.wall-gw .gw-num { font-size: 30px; }
.wall-tip {
  text-align: center;
  font-size: 11px;
  color: #5b83b5;
  padding-bottom: 4px;
}
.wall-spare {
  flex: 1;
  justify-content: center;
}
.wall-mini {
  max-height: none;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.wall-kb-tags {
  justify-content: center;
}
.wall-live {
  max-height: none;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.wall-insp {
  margin-bottom: 4px;
}
.wall-insp-chart {
  min-height: 0;
}
.wall-order {
  flex: 1;
  align-content: center;
  gap: 10px;
}
.wall-order .od-num { font-size: 26px; }
.wall-sev {
  margin-bottom: 6px;
}
.wall-top {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.wall-notify {
  flex: 1;
  align-content: center;
  gap: 10px;
}
.wall-notify .nt-item { padding: 12px 12px; }
.wall-notify .nt-val { font-size: 20px; }
.wall-ops {
  flex: 1;
  align-content: center;
  gap: 10px;
}
.wall-ops .op-item { padding: 12px 4px; }
.wall-ops .op-val { font-size: 24px; }
</style>
