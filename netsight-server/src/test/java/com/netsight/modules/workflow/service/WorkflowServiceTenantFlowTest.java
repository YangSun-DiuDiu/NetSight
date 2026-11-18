package com.netsight.modules.workflow.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.mapper.FlowUserMapper;
import org.dromara.warm.flow.core.entity.Definition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTenantFlowTest {

    @Mock private DefService defService;
    @Mock private InsService insService;
    @Mock private TaskService taskService;
    @Mock private UserService flowUserService;
    @Mock private FlowUserMapper flowUserMapper;
    @Mock private com.netsight.modules.workflow.mapper.ApprovalBillMapper approvalBillMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(defService, insService, taskService, flowUserService, flowUserMapper,
                approvalBillMapper, jdbcTemplate, eventPublisher);
    }

    @Test
    void rejectsPublishedFlowWhenTenantHasNoDefinition() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getPublishedByFlowCode("inspection_task_confirm_t17", 17L));

        assertTrue(ex.getMessage().contains("租户17"));
        verify(defService, never()).getPublishByFlowCode(anyString());
    }

    @Test
    void returnsPublishedFlowWhenTenantDefinitionExists() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        FlowDefinition definition = new FlowDefinition();
        definition.setFlowCode("inspection_task_confirm_t17");
        definition.setFlowName("点检任务确认");
        definition.setTenantId("17");
        when(defService.getPublishByFlowCode("inspection_task_confirm_t17")).thenReturn(definition);

        Definition result = service.getPublishedByFlowCode("inspection_task_confirm_t17", 17L);

        assertEquals("点检任务确认", result.getFlowName());
    }

    @Test
    void hasPublishedFlowIsTenantScoped() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        assertFalse(service.hasPublishedFlow("inspection_task_confirm_t17", 17L));
        verify(defService, never()).getPublishByFlowCode(anyString());
    }

    @Test
    void fiveArgEventConstructorStillWorks() {
        WorkflowApprovalEvent event = new WorkflowApprovalEvent(
                "out_stock", "OUT001", true, "ok", "approved");

        assertEquals("out_stock", event.getBizType());
        assertEquals(null, event.getBillId());
        assertEquals(null, event.getTenantId());
    }
}
