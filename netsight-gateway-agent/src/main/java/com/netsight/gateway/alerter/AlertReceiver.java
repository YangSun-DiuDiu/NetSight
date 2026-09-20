package com.netsight.gateway.alerter;

import com.netsight.gateway.core.ConfigHolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 本地告警接收器：独立端口（默认 :18080）接收 AlertManager Webhook，
 * 与本地管理页面（:8081）端口分离。
 * <p>
 * 路径：POST /alert/push（AlertManager webhook_config url 指向本地址）。
 * 收到后原样转交 {@link AlertForwarder} 处理。
 * </p>
 */
@Slf4j
@Component
public class AlertReceiver {

    private final ConfigHolder configHolder;
    private final AlertForwarder alertForwarder;

    private HttpServer server;

    public AlertReceiver(ConfigHolder configHolder, AlertForwarder alertForwarder) {
        this.configHolder = configHolder;
        this.alertForwarder = alertForwarder;
    }

    @jakarta.annotation.PostConstruct
    public void start() throws IOException {
        int port = configHolder.getConfig().getLocal().getAlertPort();
        server = HttpServer.create(new InetSocketAddress(port), 64);
        server.createContext("/alert/push", this::handlePush);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "alert-receiver");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("告警接收服务已启动: :{}/alert/push", port);
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handlePush(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String json = new String(body, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                respond(exchange, 400, "empty body");
                return;
            }
            alertForwarder.forward(json);
            respond(exchange, 200, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            log.error("接收告警异常: {}", e.getMessage(), e);
            respond(exchange, 500, "internal error");
        }
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
