package com.netsight.modules.workorder.controller;

import com.netsight.framework.security.LoginUser;
import com.netsight.modules.spare.mapper.SparePartMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import com.netsight.modules.workorder.service.RepairerAccountResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileWorkOrderControllerTest {

    @Mock private WorkOrderMapper orderMapper;
    @Mock private WorkOrderRecordMapper recordMapper;
    @Mock private WorkOrderPartMapper orderPartMapper;
    @Mock private RepairerAccountResolver repairerAccountResolver;
    @Mock private SparePartMapper sparePartMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentRepairerDelegatesToResolver() {
        MobileWorkOrderController controller = new MobileWorkOrderController(
                orderMapper, recordMapper, orderPartMapper, repairerAccountResolver,
                sparePartMapper);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(22L);
        loginUser.setTenantId(14L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
        Repairer repairer = new Repairer();
        repairer.setId(101L);
        when(repairerAccountResolver.resolve(any())).thenReturn(repairer);

        assertSame(repairer, controller.currentRepairer());
    }
}
