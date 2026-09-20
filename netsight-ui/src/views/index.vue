<template>
  <div class="app-container home">
    <el-row :gutter="20" class="hero-row">
      <el-col :sm="24" :lg="10" style="padding-left: 20px">
        <h2>NetSight 网络资产监控管理系统</h2>
        <p>
          NetSight（NAMS）是一套云边协同的物联网设备状态采集、分析与异常报警平台，
          统一纳管网络设备、视频监控设备、门禁安防设备三类核心资产，配套平面化运维监控大屏、
          网络拓扑自动生成、故障工单全流程管理。
        </p>
      </el-col>

      <el-col :sm="24" :lg="14">
        <div class="home-anim" title="云边协同数据流示意">
          <svg viewBox="0 0 580 300" preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">
            <defs>
              <linearGradient id="gwGrad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#205CF5"/>
                <stop offset="1" stop-color="#3A66EB"/>
              </linearGradient>
              <linearGradient id="cloudGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" stop-color="#EEF2FF"/>
                <stop offset="1" stop-color="#E0E7FF"/>
              </linearGradient>
            </defs>

            <!-- ===== 内网侧：设备 ===== -->
            <g font-size="11" fill="#475569" text-anchor="middle">
              <!-- 交换机 -->
              <rect x="15" y="168" width="44" height="30" rx="4" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <rect x="22" y="175" width="8" height="16" rx="1" fill="#205CF5"/>
              <rect x="34" y="175" width="8" height="16" rx="1" fill="#205CF5"/>
              <circle cx="26" cy="173" r="1.6" fill="#10B981"/>
              <text x="37" y="214">交换机</text>

              <!-- 摄像头 -->
              <circle cx="92" cy="183" r="15" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <circle cx="92" cy="183" r="6" fill="#205CF5"/>
              <rect x="86" y="198" width="12" height="7" rx="2" fill="#64748B"/>
              <text x="92" y="220">摄像头</text>

              <!-- 门禁 -->
              <rect x="128" y="168" width="26" height="32" rx="4" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <rect x="134" y="176" width="14" height="9" rx="2" fill="#205CF5"/>
              <circle cx="141" cy="192" r="2" fill="#10B981"/>
              <text x="141" y="214">门禁</text>

              <text x="70" y="245" fill="#94A3B8" font-size="10">内网设备 · 状态采集</text>
            </g>

            <!-- ===== 边缘网关 ===== -->
            <g>
              <rect x="185" y="60" width="95" height="62" rx="9" fill="url(#gwGrad)"/>
              <text x="232" y="86" fill="#FFFFFF" font-size="12" font-weight="600" text-anchor="middle">边缘网关</text>
              <text x="232" y="103" fill="rgba(255,255,255,.85)" font-size="9.5" text-anchor="middle">自研采集引擎</text>
              <text x="232" y="116" fill="rgba(255,255,255,.7)" font-size="9" text-anchor="middle">指标不出内网</text>
              <circle cx="200" cy="72" r="3.2" fill="#10B981" class="pulse"/>
            </g>

            <!-- ===== 云端 NetSight ===== -->
            <g>
              <rect x="295" y="40" width="145" height="102" rx="10" fill="url(#cloudGrad)" stroke="#BBD0F7" stroke-width="1.5"/>
              <text x="367" y="58" fill="#0B2A6B" font-size="12" font-weight="600" text-anchor="middle">NetSight 云平台</text>
              <!-- 三个核心模块 -->
              <rect x="305" y="72" width="40" height="26" rx="5" fill="#205CF5"/>
              <text x="325" y="89" fill="#FFFFFF" font-size="9" text-anchor="middle">事件中心</text>
              <rect x="352" y="72" width="40" height="26" rx="5" fill="#3A66EB"/>
              <text x="372" y="89" fill="#FFFFFF" font-size="9" text-anchor="middle">告警中心</text>
              <rect x="399" y="72" width="34" height="26" rx="5" fill="#1A4FD8"/>
              <text x="416" y="89" fill="#FFFFFF" font-size="9" text-anchor="middle">工单</text>
              <text x="367" y="116" fill="#64748B" font-size="9.5" text-anchor="middle">规则路由 · 多租户隔离</text>
              <text x="367" y="130" fill="#64748B" font-size="9.5" text-anchor="middle">WebSocket 双向通信 · AI 实时分析</text>
            </g>

            <!-- ===== 通知通道 ===== -->
            <g font-size="11" fill="#475569" text-anchor="middle">
              <!-- 短信 -->
              <rect x="500" y="52" width="40" height="30" rx="7" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <rect x="507" y="59" width="26" height="16" rx="3" fill="#205CF5"/>
              <path d="M507,72 l6,-6 h20" fill="none" stroke="#205CF5" stroke-width="1.5"/>
              <text x="520" y="98">短信</text>

              <!-- 公众号 -->
              <rect x="500" y="108" width="40" height="30" rx="7" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <circle cx="520" cy="120" r="6" fill="#3A66EB"/>
              <rect x="512" y="130" width="16" height="3" rx="1.5" fill="#3A66EB"/>
              <text x="520" y="154">公众号</text>

              <!-- 大屏 -->
              <rect x="500" y="164" width="40" height="26" rx="4" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1.5"/>
              <rect x="506" y="168" width="28" height="16" rx="2" fill="#1A4FD8"/>
              <rect x="516" y="190" width="8" height="5" fill="#94A3B8"/>
              <text x="520" y="210">监控大屏</text>
            </g>

            <!-- ===== 数据流连线（虚线流动） ===== -->
            <g fill="none" stroke="#93B4F5" stroke-width="1.6" class="flow-line">
              <!-- 设备 -> 网关 -->
              <path d="M52,166 C 100,135 140,125 183,112"/>
              <path d="M92,168 C 120,138 150,128 183,122"/>
              <path d="M134,166 C 150,142 165,132 183,132"/>
              <!-- 网关 -> 云端 -->
              <path d="M282,82 C 288,78 291,76 295,74"/>
              <path d="M282,100 C 288,96 291,94 295,96"/>
              <!-- 云端 -> 通知 -->
              <path d="M442,92 C 460,88 478,82 498,72"/>
              <path d="M442,104 C 460,100 478,118 498,126"/>
              <path d="M442,116 C 460,114 478,168 498,180"/>
            </g>

            <!-- 云端内部流转 -->
            <g fill="none" stroke="#9DB8F0" stroke-width="1.4" class="flow-line">
              <path d="M345,85 L 350,85"/>
              <path d="M392,85 L 397,85"/>
            </g>

            <!-- ===== 流动数据点（SMIL 动画） ===== -->
            <g>
              <!-- 设备->网关 -->
              <circle r="3.2" fill="#205CF5">
                <animateMotion dur="2.4s" repeatCount="indefinite" begin="0s" path="M52,166 C 100,135 140,125 183,112"/>
              </circle>
              <circle r="3.2" fill="#205CF5">
                <animateMotion dur="2.4s" repeatCount="indefinite" begin="0.8s" path="M92,168 C 120,138 150,128 183,122"/>
              </circle>
              <circle r="3.2" fill="#205CF5">
                <animateMotion dur="2.4s" repeatCount="indefinite" begin="1.6s" path="M134,166 C 150,142 165,132 183,132"/>
              </circle>
              <!-- 网关->云端 -->
              <circle r="2.8" fill="#3A66EB">
                <animateMotion dur="1.8s" repeatCount="indefinite" begin="0.4s" path="M282,82 C 288,78 291,76 295,74"/>
              </circle>
              <circle r="2.8" fill="#3A66EB">
                <animateMotion dur="1.8s" repeatCount="indefinite" begin="1.3s" path="M282,100 C 288,96 291,94 295,96"/>
              </circle>
              <!-- 云端->通知（告警） -->
              <circle r="3" fill="#F59E0B">
                <animateMotion dur="2.6s" repeatCount="indefinite" begin="0.2s" path="M442,92 C 460,88 478,82 498,72"/>
              </circle>
              <circle r="3" fill="#F59E0B">
                <animateMotion dur="2.6s" repeatCount="indefinite" begin="1.1s" path="M442,104 C 460,100 478,118 498,126"/>
              </circle>
              <circle r="3" fill="#F59E0B">
                <animateMotion dur="2.6s" repeatCount="indefinite" begin="2s" path="M442,116 C 460,114 478,168 498,180"/>
              </circle>
              <!-- 云端内部 -->
              <circle r="2.2" fill="#7CA0EE">
                <animateMotion dur="1.4s" repeatCount="indefinite" begin="0s" path="M345,85 L 350,85"/>
              </circle>
              <circle r="2.2" fill="#7CA0EE">
                <animateMotion dur="1.4s" repeatCount="indefinite" begin="0.7s" path="M392,85 L 397,85"/>
              </circle>
            </g>
          </svg>
          <div class="anim-caption">云边协同数据流：内网设备采集 → 边缘网关 → 云平台规则路由 → 短信 / 公众号 / 大屏触达</div>
        </div>
      </el-col>
    </el-row>

    <el-divider />

    <el-row :gutter="20">
      <el-col :span="6">
        <el-card shadow="hover" class="feature-card">
          <i class="el-icon-monitor feature-icon" />
          <h3>统一监控</h3>
          <p>自研采集引擎下沉边缘网关，指标不出内网</p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="feature-card">
          <i class="el-icon-connection feature-icon" />
          <h3>智能拓扑</h3>
          <p>主备三路上联关系自动生成网络拓扑</p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="feature-card">
          <i class="el-icon-bell feature-icon" />
          <h3>事件通知</h3>
          <p>事件中心 + 通道中心解耦，短信/公众号触达</p>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="feature-card">
          <i class="el-icon-s-order feature-icon" />
          <h3>运维闭环</h3>
          <p>告警 → 联系单 → 派单 → 维修 → 恢复全流程</p>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
export default {
  name: "Index"
}
</script>

<style scoped lang="scss">
.home {
  .hero-row {
    display: flex;
    align-items: center;
  }
  .home-anim {
    width: 100%;
    padding: 8px 0 0;
    svg {
      width: 100%;
      height: auto;
      display: block;
    }
    .anim-caption {
      text-align: center;
      color: #909399;
      font-size: 12px;
      margin-top: 2px;
    }
    .flow-line {
      stroke-dasharray: 5 5;
      animation: dashFlow 1.1s linear infinite;
    }
    .pulse {
      animation: pulse 1.6s ease-in-out infinite;
    }
    @keyframes dashFlow {
      to { stroke-dashoffset: -10; }
    }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.3; }
    }
  }
  .feature-card {
    text-align: center;
    .feature-icon {
      font-size: 36px;
      color: #205cf5;
      margin-bottom: 10px;
    }
    h3 {
      margin: 8px 0;
      font-weight: 600;
    }
    p {
      color: #909399;
      font-size: 13px;
      margin: 0;
    }
  }
}
</style>
