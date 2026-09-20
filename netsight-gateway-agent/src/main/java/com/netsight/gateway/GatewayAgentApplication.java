package com.netsight.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NetSight 边缘网关上报服务（nams-agent）启动类。
 * <p>
 * 单进程承载：上行上报（心跳/状态快照）、告警转发与缓存补传、
 * 采集清单同步（mapping.json → Prometheus targets）、
 * 本地管理页面（路由器风格，:8081，仅内网）。
 * </p>
 * 运行参数：
 * <ul>
 *   <li>-Dnams.conf=/opt/nams-gateway/conf/config.json 指定网关配置（默认同值）</li>
 *   <li>-Dnams.home=/opt/nams-gateway 指定网关工作目录（默认同值）</li>
 * </ul>
 */
@Slf4j
@EnableScheduling
@SpringBootApplication
public class GatewayAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayAgentApplication.class, args);
        log.info("==================================================");
        log.info("  NetSight nams-agent (Java) 启动完成");
        log.info("  本地管理页面 :8081（仅内网访问）");
        log.info("  告警接收     :18080（AlertManager Webhook 目标）");
        log.info("==================================================");
    }
}
