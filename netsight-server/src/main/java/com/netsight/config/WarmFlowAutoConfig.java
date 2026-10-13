package com.netsight.config;

import org.dromara.warm.plugin.modes.sb.config.BeanConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Warm-Flow 流程引擎装配配置
 *
 * 1.8.9 的 SB4 starter 为依赖聚合壳（无 class），真正的 Service Bean
 * （DefService/InsService/TaskService/HisTaskService/UserService 等）
 * 由 warm-flow-plugin-modes-sb 的 {@link BeanConfig} 通过 @Bean 注册，
 * 且该配置类带 @ConditionalOnProperty("warm-flow.enabled")，此处显式 Import 确保生效。
 */
@Configuration
@Import(BeanConfig.class)
public class WarmFlowAutoConfig {
}
