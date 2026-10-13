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
 *   /warm-flow-ui?Authorization=<token>
 * 引擎 UI 前端 JS 会自动将 URL 中的 Authorization 参数写入
 * localStorage["Warm-Authorization"]，后续对引擎 API 的请求自动携带该 token，
 * 后端 JwtAuthenticationFilter 解析出当前用户 → TenantHandler/PermissionHandler 生效。
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
    let url = '/warm-flow-ui'
    const params = []
    if (token) params.push('Authorization=' + encodeURIComponent(token))
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
