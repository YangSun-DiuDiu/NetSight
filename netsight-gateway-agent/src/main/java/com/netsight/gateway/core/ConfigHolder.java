package com.netsight.gateway.core;

import com.netsight.gateway.common.R;
import com.netsight.gateway.common.ResultCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关本地配置持有器：加载并维护 conf/config.json。
 * <p>
 * 配置权威在云端（mapping.json/Token 以下发为准）；本地 config.json 仅承载
 * 连接配置（云端地址/Token/周期）与本地管理项，与部署方案 4.1 对应。
 * </p>
 */
@Slf4j
@Component
public class ConfigHolder {

    /** 默认网关工作目录（与部署方案 3.1 一致），可用 -Dnams.home 覆盖 */
    public static final String DEFAULT_HOME = "/opt/nams-gateway";

    /** 默认配置文件路径 */
    public static final String DEFAULT_CONF = "/opt/nams-gateway/conf/config.json";

    /** config.json 为 snake_case（gateway_code/base_url 等），Jackson 3 需显式配置命名策略 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private String homeDir;
    private String confPath;
    private GatewayConfig config;

    public ConfigHolder() {
        this.homeDir = System.getProperty("nams.home", DEFAULT_HOME);
        this.confPath = System.getProperty("nams.conf", DEFAULT_CONF);
    }

    @PostConstruct
    public void init() {
        // 本地开发/测试时未部署到 /opt 下：回退到工程 conf/config.json
        if (!Files.exists(Paths.get(confPath))) {
            Path localConf = Paths.get("conf/config.json");
            if (Files.exists(localConf)) {
                this.confPath = localConf.toAbsolutePath().toString();
            }
        }
        reload();
    }

    /**
     * 重新加载 config.json。
     */
    public synchronized R<Void> reload() {
        try {
            Path p = Paths.get(confPath);
            if (!Files.exists(p)) {
                log.error("配置文件不存在: {}", confPath);
                return R.fail(ResultCode.BAD_REQUEST, "配置文件不存在: " + confPath);
            }
            String json = Files.readString(p);
            GatewayConfig cfg = mapper.readValue(json, GatewayConfig.class);
            normalize(cfg);
            this.config = cfg;
            log.info("config.json 已加载: gateway_code={}, cloud={}",
                    cfg.getGatewayCode(), cfg.getCloud().getBaseUrl());
            return R.ok(null);
        } catch (Exception e) {
            log.error("config.json 解析失败: {}", e.getMessage(), e);
            return R.fail(ResultCode.BAD_REQUEST, "config.json 解析失败: " + e.getMessage());
        }
    }

    private void normalize(GatewayConfig cfg) {
        if (cfg.getCloud() == null) {
            cfg.setCloud(new Cloud());
        }
        if (cfg.getCloud().getHeartbeatInterval() <= 0) {
            cfg.getCloud().setHeartbeatInterval(30);
        }
        if (cfg.getCloud().getStatusInterval() <= 0) {
            cfg.getCloud().setStatusInterval(30);
        }
        if (cfg.getLinks() == null) {
            cfg.setLinks(new ArrayList<>());
        }
        if (cfg.getPrometheus() == null) {
            cfg.setPrometheus(new Prometheus());
        }
        if (cfg.getLocal() == null) {
            cfg.setLocal(new Local());
        }
        if (cfg.getLocal().getAdmin() == null) {
            cfg.getLocal().setAdmin(new Admin());
        }
    }

    public GatewayConfig getConfig() {
        return config;
    }

    public String getHomeDir() {
        return homeDir;
    }

    public String getConfDir() {
        return Paths.get(confPath).getParent().toAbsolutePath().toString();
    }

    public String getTargetsDir() {
        return Paths.get(getConfDir(), "targets").toString();
    }

    public String getCacheDir() {
        return Paths.get(homeDir, "cache").toString();
    }

    public String getLogDir() {
        return Paths.get(homeDir, "logs").toString();
    }

    /** ============ 配置模型（与 config.json 结构一一对应） ============ */

    @Data
    public static class GatewayConfig {
        /** 网关编码（云端注册时生成，唯一） */
        private String gatewayCode;
        private String gatewayName;
        /** 采集清单路径（mapping.json），默认 conf/mapping.json */
        private String mappingPath;
        private Cloud cloud;
        private List<Link> links;
        private Prometheus prometheus;
        private Local local;
    }

    @Data
    public static class Cloud {
        /** 云端入口，如 http://192.168.1.55（走 Nginx 80 /edge/ 反代） */
        private String baseUrl;
        /** 网关 Token（X-Gateway-Token，云端绑定租户） */
        private String gatewayToken;
        /** 租户 ID（X-Netsight-Tenant-Id） */
        private Long tenantId;
        /** 租户级 Webhook Token（X-Netsight-Webhook-Token，告警转投用） */
        private String alertWebhookToken;
        /** 心跳周期（秒） */
        private int heartbeatInterval = 30;
        /** 状态快照周期（秒） */
        private int statusInterval = 30;
        /** 请求超时（毫秒） */
        private int timeoutMs = 5000;
    }

    @Data
    public static class Link {
        /** wired | wifi | 4g5g */
        private String type;
        /** 数字越小优先级越高 */
        private int priority;
        private boolean enabled = true;
    }

    @Data
    public static class Prometheus {
        private String baseUrl = "http://127.0.0.1:9090";
        private int queryTimeoutMs = 3000;
        /** 快照数据源：prometheus（查 device_up）或 probe（自研探针内存上报） */
        private String source = "prometheus";
        /** probe 数据源的状态文件路径（探针/采集软件周期性写入，热可改） */
        private String probeStatusFile = "/opt/nams-gateway/data/device_status.json";
    }

    @Data
    public static class Local {
        /** 管理页面端口 */
        private int webPort = 8081;
        /** 告警接收端口 */
        private int alertPort = 18080;
        /** 清单同步周期（秒） */
        private int syncInterval = 60;
        private Admin admin;
    }

    @Data
    public static class Admin {
        private String username = "admin";
        /** BCrypt 哈希；为空时使用内置默认出厂密码（生产必须预置） */
        private String passwordHash;
        /** 首次登录是否强制修改密码 */
        private boolean forceChangePwd = true;
    }
}
