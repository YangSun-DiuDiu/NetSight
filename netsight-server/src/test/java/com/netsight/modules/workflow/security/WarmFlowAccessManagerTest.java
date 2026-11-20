package com.netsight.modules.workflow.security;

import com.netsight.framework.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 流程设计器与 Warm-Flow 引擎接口的访问控制测试。
 *
 * 策略：只有 super_admin 可以访问 /warm-flow/**，不论请求方法。
 */
class WarmFlowAccessManagerTest {

    private final WarmFlowAccessManager manager = new WarmFlowAccessManager();

    @Test
    void deniesAnonymousRequest() {
        AuthorizationDecision decision = manager.authorize(() -> null, mock(RequestAuthorizationContext.class));

        assertNotNull(decision);
        assertFalse(decision.isGranted());
    }

    @Test
    void deniesTenantAdminRequest() {
        AuthorizationDecision decision = manager.authorize(
                authenticationWithRoles("tenant_admin"),
                mock(RequestAuthorizationContext.class));

        assertNotNull(decision);
        assertFalse(decision.isGranted());
    }

    @Test
    void deniesOpsRequest() {
        AuthorizationDecision decision = manager.authorize(
                authenticationWithRoles("ops"),
                mock(RequestAuthorizationContext.class));

        assertNotNull(decision);
        assertFalse(decision.isGranted());
    }

    @Test
    void allowsSuperAdminRequest() {
        AuthorizationDecision decision = manager.authorize(
                authenticationWithRoles("super_admin"),
                mock(RequestAuthorizationContext.class));

        assertNotNull(decision);
        assertTrue(decision.isGranted());
    }

    private Supplier<Authentication> authenticationWithRoles(String... roles) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(100L);
        loginUser.setTenantId(17L);
        loginUser.setRoles(Set.of(roles));
        loginUser.setPermissions(Set.of());
        loginUser.setIsSuperAdmin(Set.of(roles).contains("super_admin"));
        return () -> new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
    }
}
