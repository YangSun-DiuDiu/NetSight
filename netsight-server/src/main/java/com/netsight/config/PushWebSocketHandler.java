package com.netsight.config;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.extern.slf4j.Slf4j;

/**
 * 运维大屏 / 实时页面 WebSocket 推送通道（原生 WebSocket，无 STOMP）
 * 端点：/ws/push（前端 new WebSocket('ws://host/ws/push')）
 * 消息格式：{"type":"device-status|event|order|dashboard-refresh","data":{...},"time":"..."}
 * 说明：网关长连接走 STOMP /ws/gateway（WebSocketConfig 已配置），本通道面向浏览器实时页面
 */
@Slf4j
public class PushWebSocketHandler extends TextWebSocketHandler {

    private static final CopyOnWriteArraySet<WebSocketSession> SESSIONS = new CopyOnWriteArraySet<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 广播消息（业务模块调用：设备状态变更 / 新告警 / 工单状态变更 / 大屏刷新）
     *
     * @param type    消息类型：device-status / event / order / dashboard-refresh
     * @param payload 业务数据（可空）
     */
    public static void broadcast(String type, Object payload) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(
                    Map.of("type", type, "data", payload == null ? Map.of() : payload,
                            "time", LocalDateTime.now().format(TIME_FMT)));
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : SESSIONS) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                } catch (Exception ignored) {
                    // 单会话失败不影响其他会话
                }
            }
        } catch (Exception e) {
            // 序列化失败仅记录，不抛到业务
        }
    }

    /** 当前在线连接数（监控/调试用） */
    public static int onlineCount() {
        return SESSIONS.size();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SESSIONS.remove(session);
    }
}
