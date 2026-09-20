package com.netsight.modules.alert.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.alert.entity.NotificationTemplate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {

    /**
     * V1.1.10：复制租户1（默认租户）完整模板集到新租户，一条 SQL 原子完成。
     * <p>@InterceptorIgnore(tenantLine=true)：绕过多租户拦截器——复制源为租户1 公共模板，
     * INSERT 目标租户为新租户（非当前登录租户），均不能被当前租户上下文拦截过滤。
     * 返回复制条数。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO notification_template" +
            " (tenant_id, template_code, template_name, channel_type, content, enabled, del_flag, create_time, update_time)" +
            " SELECT #{newTenantId}, template_code, template_name, channel_type, content, enabled, 0, NOW(), NOW()" +
            " FROM notification_template WHERE tenant_id = 1 AND del_flag = 0")
    int copyTemplatesFromDefault(@Param("newTenantId") Long newTenantId);
}
