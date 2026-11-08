<template>
  <el-tag :type="type || 'info'" size="mini">{{ text || '—' }}</el-tag>
</template>

<script>
/**
 * 统一状态标签
 * 全站审批状态/节点动作都走这一处，禁止各页面再手写 el-tag 三元判断。
 */
export default {
  name: 'StatusTag',
  props: {
    text: { type: String, default: '' },
    type: { type: String, default: 'info' }
  }
}
</script>
