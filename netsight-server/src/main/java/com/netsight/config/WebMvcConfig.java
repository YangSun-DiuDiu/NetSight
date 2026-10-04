package com.netsight.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：静态资源映射（上传文件访问）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload-dir:/opt/nams-server/uploads}")
    private String uploadDir;

    @Value("${file.access-prefix:/uploads}")
    private String accessPrefix;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传文件静态映射：/uploads/** → 磁盘目录
        registry.addResourceHandler(accessPrefix + "/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}
