package com.netsight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import lombok.extern.slf4j.Slf4j;

/**
 * NetSight 网络资产监控管理系统启动类
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.netsight.modules.**.mapper")
@Slf4j
public class NetsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetsightApplication.class, args);
    }
}
