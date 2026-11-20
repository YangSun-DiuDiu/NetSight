package com.netsight.modules.workflow.security;

import com.netsight.framework.security.LoginUser;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Warm-Flow 设计器/引擎接口访问控制。
 *
 * 流程定义只能由超级管理员创建、复制和维护；租户管理员、运维人员只能
 * 使用业务审批入口（我的发起、统一待办），不能访问引擎设计接口。
 */
@Component
public class WarmFlowAccessManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                           RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser loginUser)) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(Boolean.TRUE.equals(loginUser.getIsSuperAdmin()));
    }
}
