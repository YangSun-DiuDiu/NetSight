package com.netsight.gateway.link;

import com.netsight.gateway.connector.CloudClient;
import com.netsight.gateway.core.ConfigHolder;
import com.netsight.gateway.core.RuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 多链路管理器（骨架）：按优先级探测云端连通，维护当前生效链路。
 * <p>
 * config.json links 定义候选链路（wired/wifi/4g5g，数字越小优先级越高）；
 * 每 30s 探测云端 /actuator/health，连通则标记在线并记录当前链路。
 * 主备自动切换的完整策略（断 30s 内切备份、切换事件上报）后续版本增强。
 * </p>
 */
@Slf4j
@Component
public class LinkManager {

    private final ConfigHolder configHolder;
    private final CloudClient cloudClient;
    private final RuntimeState runtimeState;

    public LinkManager(ConfigHolder configHolder, CloudClient cloudClient, RuntimeState runtimeState) {
        this.configHolder = configHolder;
        this.cloudClient = cloudClient;
        this.runtimeState = runtimeState;
    }

    @Scheduled(fixedDelay = 30000)
    public void probe() {
        ConfigHolder.GatewayConfig cfg = configHolder.getConfig();
        if (cfg == null || cfg.getCloud() == null || cfg.getCloud().getBaseUrl() == null) {
            return;
        }
        String base = cfg.getCloud().getBaseUrl().replaceAll("/+$", "");
        boolean ok = cloudClient.probeCloud(base);
        String link = pickCurrentLink();
        runtimeState.markCloud(ok, link);
        log.debug("链路探测: {} 云端 {}（当前链路 {}）", ok ? "连通" : "不通", base, link);
    }

    /** 选择当前链路：取 enabled 中 priority 最小者（探测通过时优先于连通链路，骨架简化） */
    private String pickCurrentLink() {
        List<ConfigHolder.Link> links = configHolder.getConfig().getLinks();
        if (links == null || links.isEmpty()) {
            return "wired";
        }
        return links.stream()
                .filter(l -> l.isEnabled())
                .min(Comparator.comparingInt(ConfigHolder.Link::getPriority))
                .map(ConfigHolder.Link::getType)
                .orElse("wired");
    }
}
