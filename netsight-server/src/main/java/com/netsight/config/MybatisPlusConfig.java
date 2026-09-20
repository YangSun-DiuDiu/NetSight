package com.netsight.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.netsight.framework.security.SecurityUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * MyBatis-Plus 配置
 * 多租户插件：SQL 层自动追加 tenant_id 条件，实现数据隔离（业务代码无感知）
 * 分页插件：物理分页，前端分页参数直接传入
 */
@Configuration
@Slf4j
public class MybatisPlusConfig {

    /** 系统公共表，不参与租户过滤 */
    private static final Set<String> IGNORE_TABLES = Set.of(
            "sys_dict_type", "sys_dict_data", "sys_config", "sys_tenant",
            // 角色/权限体系为全局公共数据，不做租户隔离
            "sys_role", "sys_permission", "sys_user_role", "sys_role_permission"
    );

    /**
     * 多租户插件 + 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 租户行级拦截器：自动追加 tenant_id 条件
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                // 已登录：返回当前租户ID，自动隔离租户数据（未登录返回 0，配合 ignoreTable 不会实际拼接）
                return new LongValue(SecurityUtils.getTenantId());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 未登录（登录认证等场景）：跳过所有表的租户过滤，保证按手机号能查到任意租户的用户
                if (!SecurityUtils.isAuthenticated()) {
                    return true;
                }
                // 超级管理员：不受租户隔离，可管理全部租户数据
                if (SecurityUtils.isSuperAdmin()) {
                    return true;
                }
                // 已登录：系统公共表忽略租户过滤
                return IGNORE_TABLES.contains(tableName);
            }
        });
        interceptor.addInnerInterceptor(tenantInterceptor);

        // 2. 分页拦截器
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);

        return interceptor;
    }
}
