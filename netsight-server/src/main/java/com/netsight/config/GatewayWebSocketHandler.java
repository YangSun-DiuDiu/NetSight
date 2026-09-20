package com.netsight.config;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 边缘网关长连接通道（预留）
 * 当前网关采用 HTTP 周期上报（/edge/report/status、/edge/report/heartbeat），
 * 本通道为后续"云端→网关"指令下发预留：网关连上后可通过 /ws/gateway 接收云端指令。
 */
@Slf4j
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    /** 网关连接会话表：key=sessionId，value=session */
    private static final Map<String, WebSocketSession> GATEWAY_SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        GATEWAY_SESSIONS.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 预留：解析网关心跳/指令应答，后续按需实现
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        GATEWAY_SESSIONS.remove(session.getId());
    }

    /** 向指定网关下发指令（预留） */
    public static boolean sendToGateway(String gatewayToken, String payload) {
        for (WebSocketSession s : GATEWAY_SESSIONS.values()) {
            try {
                s.sendMessage(new TextMessage(payload));
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
