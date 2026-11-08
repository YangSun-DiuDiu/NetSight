package com.netsight.modules.workorder.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepairerAccountResolverTest {

    @Mock
    private RepairerMapper repairerMapper;

    @InjectMocks
    private RepairerAccountResolver resolver;

    private Repairer repairer(Long id, Long tenantId, Long userId, Integer status) {
        Repairer repairer = new Repairer();
        repairer.setId(id);
        repairer.setTenantId(tenantId);
        repairer.setUserId(userId);
        repairer.setRepairerType(userId == null ? 0 : 1);
        repairer.setName("维修员甲");
        repairer.setPhone("19900000002");
        repairer.setStatus(status);
        return repairer;
    }

    private LoginUser loginUser(Long repairerId, Long userId, Long tenantId, String phone) {
        LoginUser loginUser = new LoginUser();
        loginUser.setRepairerId(repairerId);
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setPhone(phone);
        return loginUser;
    }

    @Test
    void resolveUsesRepairerIdClaimFirst() {
        Repairer repairer = repairer(101L, 14L, 22L, 1);
        when(repairerMapper.selectOne(any())).thenReturn(repairer);

        Repairer result = resolver.resolve(loginUser(101L, 22L, 14L, "19900000002"));

        assertEquals(101L, result.getId());
    }

    @Test
    void resolveFallsBackToUserIdWhenOldTokenHasNoRepairerId() {
        Repairer repairer = repairer(101L, 14L, 22L, 1);
        when(repairerMapper.selectList(any())).thenReturn(List.of(repairer));

        Repairer result = resolver.resolve(loginUser(null, 22L, 14L, "19900000002"));

        assertEquals(101L, result.getId());
    }

    @Test
    void resolveRejectsResignedRepairer() {
        Repairer repairer = repairer(101L, 14L, 22L, 2);
        when(repairerMapper.selectOne(any())).thenReturn(repairer);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> resolver.resolve(loginUser(101L, 22L, 14L, "19900000002")));

        assertEquals("当前维修人员已离职，不能登录 H5", exception.getMessage());
    }

    @Test
    void resolveRejectsMultipleLegacyMatches() {
        Repairer first = repairer(101L, 14L, null, 1);
        Repairer second = repairer(102L, 14L, null, 1);
        when(repairerMapper.selectList(any())).thenReturn(List.of(first, second));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> resolver.resolve(loginUser(null, null, 14L, "19900000002")));

        assertEquals("手机号关联多个维修人员账号，请联系管理员处理", exception.getMessage());
    }
}
