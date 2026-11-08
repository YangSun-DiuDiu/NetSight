package com.netsight.modules.workorder.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.mapper.NotificationContactMapper;
import com.netsight.modules.alert.mapper.NotificationRuleMapper;
import com.netsight.modules.system.entity.SysUser;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.workflow.mapper.ApprovalBillMapper;
import com.netsight.modules.workflow.service.WorkflowService;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTest {

    @Mock private WorkOrderMapper orderMapper;
    @Mock private WorkOrderRecordMapper recordMapper;
    @Mock private WorkOrderPartMapper orderPartMapper;
    @Mock private RepairerMapper repairerMapper;
    @Mock private NotificationRuleMapper ruleMapper;
    @Mock private NotificationContactMapper contactMapper;
    @Mock private WorkOrderNotifier notifier;
    @Mock private WorkflowService workflowService;
    @Mock private ApprovalBillMapper approvalBillMapper;
    @Mock private SysUserMapper sysUserMapper;

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(orderMapper, recordMapper, orderPartMapper,
                repairerMapper, ruleMapper, contactMapper, notifier, workflowService,
                approvalBillMapper, sysUserMapper);
    }

    private Repairer repairer(Integer type, Long userId) {
        Repairer repairer = new Repairer();
        repairer.setId(101L);
        repairer.setRepairerType(type);
        repairer.setUserId(userId);
        repairer.setStatus(1);
        return repairer;
    }

    private SysUser user(Integer status) {
        SysUser user = new SysUser();
        user.setId(22L);
        user.setStatus(status);
        return user;
    }

    @Test
    void boundEnabledAccountPassesValidation() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(1));

        assertDoesNotThrow(() -> service.validateRepairerAccount(repairer(1, 22L)));
    }

    @Test
    void disabledAccountIsRejected() {
        when(sysUserMapper.selectById(22L)).thenReturn(user(0));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.validateRepairerAccount(repairer(1, 22L)));

        assertEquals("该维修人员账号已被禁用，无法接单", exception.getMessage());
    }

    @Test
    void externalRepairerWithoutAccountIsRejected() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.validateRepairerAccount(repairer(2, null)));

        assertEquals("该外聘维修人员未绑定登录账号，无法 H5 接单，请先补建账号", exception.getMessage());
    }

    @Test
    void legacyUnboundRepairerStillCanBeDispatched() {
        assertDoesNotThrow(() -> service.validateRepairerAccount(repairer(0, null)));
    }
}
