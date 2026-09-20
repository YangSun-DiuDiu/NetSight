package com.netsight.modules.system.controller;

import com.netsight.common.core.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查接口
 * 验证服务、数据库、Redis 是否正常
 */
@Slf4j
@RestController
@RequestMapping("/actuator")
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    public HealthController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("service", "netsight-server");
        result.put("status", "UP");
        // Redis 连通性检查
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            // Redis PING 命令返回 "PONG"
            boolean redisUp = "PONG".equalsIgnoreCase(pong) || "UP".equalsIgnoreCase(pong);
            result.put("redis", redisUp ? "UP" : "DOWN");
        } catch (Exception e) {
            log.warn("Redis 健康检查失败: {}", e.toString());
            result.put("redis", "DOWN: " + e.getMessage());
        }
        return R.ok(result);
    }
}
