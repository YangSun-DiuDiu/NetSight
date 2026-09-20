package com.netsight.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 配置（纯原生 WebSocket，统一走 /ws/push）
 *
 * 说明：Spring 的 @EnableWebSocketMessageBroker（STOMP）与 WebSocketConfigurer（原生 handler）
 * 共存时，原生 handler 注册会被忽略（/ws/push 落入 DispatcherServlet 静态资源而 404/200），
 * 因此本项目统一采用原生 WebSocket：
 *   - /ws/push   ：浏览器大屏/实时页面推送通道（设备状态/告警事件/工单变更）
 *   - /ws/gateway：边缘网关长连接通道（预留，当前网关采用 HTTP 周期上报）
 * 前端 new WebSocket('ws://host/ws/push') 直接使用，无 SockJS/STOMP 依赖。
 */
@Configuration
@EnableWebSocket
@Slf4j
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 浏览器实时推送通道
        registry.addHandler(new PushWebSocketHandler(), "/ws/push")
                .setAllowedOriginPatterns("*");
        // 边缘网关长连接通道（预留）
        registry.addHandler(new GatewayWebSocketHandler(), "/ws/gateway")
                .setAllowedOriginPatterns("*");
    }
}
