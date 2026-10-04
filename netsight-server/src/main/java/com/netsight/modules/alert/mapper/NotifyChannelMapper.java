package com.netsight.modules.alert.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.alert.entity.NotifyChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NotifyChannelMapper extends BaseMapper<NotifyChannel> {

    /**
     * 系统级通道查询（如登录验证码短信通道）：登录前无租户上下文，
     * 必须绕过 TenantLineInnerInterceptor，按名称 + 指定租户精确查。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM notify_channel WHERE channel_name = #{name} " +
            "AND tenant_id = #{tenantId} AND enabled = 1 AND del_flag = 0 LIMIT 1")
    NotifyChannel selectSystemChannel(@Param("name") String name, @Param("tenantId") Long tenantId);
}
