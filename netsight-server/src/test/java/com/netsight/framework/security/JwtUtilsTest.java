package com.netsight.framework.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret",
                "0123456789abcdef0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(jwtUtils, "expireMinutes", 120L);
        jwtUtils.init();
    }

    @Test
    void tokenCarriesRepairerId() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(22L);
        loginUser.setTenantId(14L);
        loginUser.setRepairerId(101L);
        loginUser.setRoles(java.util.Set.of("repairer"));

        String token = jwtUtils.createToken(loginUser);
        Object value = jwtUtils.parseToken(token).get("repairerId");

        assertEquals(101L, ((Number) value).longValue());
    }
}
