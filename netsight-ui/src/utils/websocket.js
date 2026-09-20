/**
 * WebSocket 实时推送封装（NetSight 第 6 周）
 * 原生 WebSocket 通道：/ws/push
 * 消息格式：{"type":"device-status|event|order","data":{...},"time":"..."}
 * 用法：
 *   import { connectWebSocket, onWebSocketMessage, disconnectWebSocket } from '@/utils/websocket'
 *   connectWebSocket()
 *   onWebSocketMessage('event', (data) => {...})
 *   disconnectWebSocket()
 */
let ws = null
let reconnectTimer = null
let heartbeatTimer = null
let manualClosed = false
const handlers = {} // type -> [fn, fn, ...]

function getWsUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
  // 开发环境：dev server 的 ws 代理在 Node16+Windows 上不稳定（unhandled ECONNRESET 会崩进程），
  // 直接连后端 8080；生产环境：走同源 /ws/push（Nginx 反代到后端）
  if (process.env.NODE_ENV === 'development') {
    return protocol + 'localhost:8080/ws/push'
  }
  return protocol + window.location.host + '/ws/push'
}

function dispatch(type, data) {
  const fns = handlers[type] || []
  fns.forEach(fn => {
    try {
      fn(data)
    } catch (e) {
      console.error('[websocket] handler error:', e)
    }
  })
  // 广播类型（所有消息都通知一次）
  const allFns = handlers['*'] || []
  allFns.forEach(fn => {
    try {
      fn({ type, data })
    } catch (e) {
      console.error('[websocket] handler error:', e)
    }
  })
}

function startHeartbeat() {
  stopHeartbeat()
  // 30s 未收到消息视为断开，主动重连（服务端无心跳协议，用定时探测）
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('ping')
    }
  }, 30000)
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function scheduleReconnect() {
  if (manualClosed || reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectWebSocket()
  }, 5000)
}

export function connectWebSocket() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return
  }
  manualClosed = false
  try {
    ws = new WebSocket(getWsUrl())
  } catch (e) {
    console.error('[websocket] 连接失败:', e)
    scheduleReconnect()
    return
  }
  ws.onopen = () => {
    console.log('[websocket] 连接建立')
    startHeartbeat()
  }
  ws.onmessage = (evt) => {
    try {
      const msg = JSON.parse(evt.data)
      if (msg && msg.type) {
        dispatch(msg.type, msg.data)
      }
    } catch (e) {
      // 心跳等非 JSON 消息忽略
    }
  }
  ws.onclose = () => {
    console.log('[websocket] 连接关闭')
    stopHeartbeat()
    if (!manualClosed) {
      scheduleReconnect()
    }
  }
  ws.onerror = () => {
    console.error('[websocket] 连接错误')
    try { ws.close() } catch (e) { /* noop */ }
  }
}

export function onWebSocketMessage(type, fn) {
  if (!handlers[type]) {
    handlers[type] = []
  }
  handlers[type].push(fn)
  return () => offWebSocketMessage(type, fn)
}

export function offWebSocketMessage(type, fn) {
  if (!handlers[type]) return
  const idx = handlers[type].indexOf(fn)
  if (idx > -1) {
    handlers[type].splice(idx, 1)
  }
}

export function disconnectWebSocket() {
  manualClosed = true
  stopHeartbeat()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (ws) {
    try { ws.close() } catch (e) { /* noop */ }
    ws = null
  }
}
