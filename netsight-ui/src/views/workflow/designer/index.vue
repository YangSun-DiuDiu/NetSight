<template>
  <div class="app-container designer-container">
    <iframe
      v-if="src"
      :src="src"
      class="designer-frame"
      frameborder="0"
    />
  </div>
</template>

<script>
import { getToken } from '@/utils/auth'

/**
 * Warm-Flow 流程设计器（iframe 集成）
 *
 * 引擎自带 Vue3 设计器 UI（/warm-flow-ui），通过 URL 参数注入 JWT：
 *   /warm-flow-ui?Authorization=Bearer%20<token>
 *
 * 注意：设计器 JS 会把 URL 中的 Authorization 参数原样放到后续请求的
 * Authorization header 里（不自动加 Bearer 前缀），而后端 JwtAuthenticationFilter
 * 要求 header 以 "Bearer " 开头才会解析。因此这里必须在 URL 参数里就拼好
 * "Bearer " 前缀，encodeURIComponent 会把空格编码为 %20。
 *
 * 参数：flowId（可选）—— 编辑指定流程定义；为空则新建流程。
 */
export default {
  name: 'WorkflowDesigner',
  data() {
    return { src: '' }
  },
  created() {
    const token = getToken()
    const flowId = this.$route.query.flowId || ''
    let url = '/warm-flow-ui/index.html'
    const params = []
    if (token) params.push('Authorization=' + encodeURIComponent('Bearer ' + token))
    if (flowId) params.push('flowId=' + flowId)
    if (params.length) url += '?' + params.join('&')
    this.src = url
  }
}
</script>

<style scoped>
.designer-container {
  padding: 0;
  height: calc(100vh - 84px);
}
.designer-frame {
  width: 100%;
  height: 100%;
  border: none;
  border-radius: 12px;
}
</style>
