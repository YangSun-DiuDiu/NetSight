package com.netsight.modules.workflow.handler;

import com.netsight.framework.security.SecurityUtils;
import org.dromara.warm.flow.core.handler.TenantHandler;

/**
 * Warm-Flow 租户处理器
 *
 * 引擎在写/读 flow_* 表时通过本实现填充与过滤 tenant_id（varchar 类型）。
 * 取值与业务侧一致：当前登录用户的租户ID（未登录返回 0）。
 *
 * 配置入口：application.yml → warm-flow.tenant-handler-path
 */
public class NamsTenantHandler implements TenantHandler {

    @Override
    public String getTenantId() {
        return String.valueOf(SecurityUtils.getTenantId());
    }
}
