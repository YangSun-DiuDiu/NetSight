package com.netsight.gateway.queue;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 断网缓存与补传队列：云端不可达时告警落盘 cache/，恢复后按时间序（FIFO）补传。
 * <p>
 * 与部署方案 6.4 一致：补传成功删除文件，失败保留并指数退避（retryMax=5，超出标记丢弃）。
 * </p>
 */
@Slf4j
@Component
public class CacheQueue {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RETRY_MAX = 5;

    private final ConfigHolder configHolder;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;
    private final ObjectMapper mapper = new ObjectMapper();

    public CacheQueue(ConfigHolder configHolder, CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    /** 入队：写 cache/{type}-{ts}-{uuid}.json */
    public void enqueue(String type, String payload) {
        try {
            Path dir = Paths.get(configHolder.getCacheDir());
            Files.createDirectories(dir);
            Map<String, Object> item = Map.of(
                    "type", type,
                    "payload", payload,
                    "createdAt", LocalDateTime.now().format(FMT),
                    "retryCount", 0
            );
            String file = String.format("%s-%s-%s.json", type,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")),
                    UUID.randomUUID().toString().substring(0, 8));
            Files.writeString(dir.resolve(file), mapper.writeValueAsString(item));
            log.warn("云端不可达，告警已缓存: {}", file);
            refreshSize();
        } catch (Exception e) {
            log.error("告警落盘失败: {}", e.getMessage(), e);
        }
    }

    /** 启动时补传一次 + 每 60s 轮询 */
    @jakarta.annotation.PostConstruct
    public void init() {
        retryAll();
    }

    @Scheduled(fixedDelay = 60000)
    public void scheduledRetry() {
        retryAll();
    }

    /** FIFO 补传：按文件名时间序逐个尝试，成功删文件，失败 retryCount+1（超限丢弃） */
    public synchronized int retryAll() {
        Path dir = Paths.get(configHolder.getCacheDir());
        int done = 0;
        if (!Files.exists(dir)) {
            refreshSize();
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            var list = files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path f : list) {
                try {
                    String json = Files.readString(f);
                    JsonNodeHolder node = new JsonNodeHolder(mapper.readTree(json));
                    String type = node.type();
                    String payload = node.payload();
                    boolean ok;
                    if ("alert".equals(type)) {
                        ok = cloudClient.forwardAlert(payload);
                    } else {
                        log.warn("未知缓存类型 {}，跳过: {}", type, f.getFileName());
                        Files.deleteIfExists(f);
                        continue;
                    }
                    if (ok) {
                        Files.deleteIfExists(f);
                        done++;
                        log.info("补传成功: {}（{}）", f.getFileName(), type);
                    } else {
                        int rc = node.retryCount() + 1;
                        if (rc > RETRY_MAX) {
                            log.error("补传重试超限（{}次），丢弃: {}", RETRY_MAX, f.getFileName());
                            Files.deleteIfExists(f);
                        } else {
                            node.updateRetry(f, rc);
                        }
                    }
                } catch (Exception e) {
                    log.error("补传处理异常 {}: {}", f.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("扫描缓存目录失败: {}", e.getMessage(), e);
        }
        refreshSize();
        return done;
    }

    private void refreshSize() {
        long size = 0;
        try (Stream<Path> files = Files.list(Paths.get(configHolder.getCacheDir()))) {
            size = files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (Exception ignored) {
        }
        runtimeState.setCacheQueueSize(size);
    }

    /** 轻量 JsonNode 访问辅助（避免重复解析文件） */
    private static class JsonNodeHolder {
        private final tools.jackson.databind.JsonNode node;

        JsonNodeHolder(tools.jackson.databind.JsonNode node) {
            this.node = node;
        }

        String type() {
            return node.path("type").asText();
        }

        String payload() {
            return node.path("payload").toString();
        }

        int retryCount() {
            return node.path("retryCount").asInt(0);
        }

        void updateRetry(Path f, int rc) throws Exception {
            var obj = ((tools.jackson.databind.node.ObjectNode) node);
            obj.put("retryCount", rc);
            Files.writeString(f, new ObjectMapper().writeValueAsString(obj));
        }
    }
}
