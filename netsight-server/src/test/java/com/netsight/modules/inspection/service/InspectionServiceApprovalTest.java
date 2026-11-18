package com.netsight.modules.inspection.service;

import com.netsight.framework.security.LoginUser;
import com.netsight.modules.inspection.entity.InspectionRecord;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionItemMapper;
import com.netsight.modules.inspection.mapper.InspectionPlanMapper;
import com.netsight.modules.inspection.mapper.InspectionRecordMapper;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.system.mapper.SysUserMapper;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.service.WorkflowService;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点检提交自动发起审批的单元测试。
 */
@ExtendWith(MockitoExtension.class)
class InspectionServiceApprovalTest {

    @Mock private InspectionItemMapper itemMapper;
    @Mock private InspectionPlanMapper planMapper;
    @Mock private InspectionTaskMapper taskMapper;
    @Mock private InspectionRecordMapper recordMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private WorkOrderService workOrderService;
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private WorkflowService workflowService;

    private InspectionService service;

    @BeforeEach
    void setUp() {
        service = new InspectionService(itemMapper, planMapper, taskMapper, recordMapper, deviceMapper,
                userMapper, workOrderService, workOrderMapper, workflowService);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(30L);
        loginUser.setUsername("nb04_admin");
        loginUser.setRealName("租户管理员");
        loginUser.setTenantId(17L);
        loginUser.setIsSuperAdmin(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitStartsInspectionApprovalAndMarksTaskProcessing() {
        InspectionTask task = new InspectionTask();
        task.setId(301L);
        task.setTenantId(17L);
        task.setTaskNo("INSP20261118001");
        task.setStatus(1);
        task.setAttemptNo(1);
        task.setAssigneeId(30L);
        task.setAssigneeName("租户管理员");
        when(taskMapper.selectById(301L)).thenReturn(task);
        when(recordMapper.insert(any(InspectionRecord.class))).thenReturn(1);
        when(workflowService.hasPublishedFlow("inspection_task_confirm_t17", 17L)).thenReturn(true);

        ApprovalBill bill = new ApprovalBill();
        bill.setId(901L);
        when(workflowService.startForTenant(eq(17L), eq(30L), eq("租户管理员"),
                eq("inspection_task_confirm_t17"), eq("inspection_task_confirm"),
                eq("INSP20261118001"), anyString())).thenReturn(bill);
        when(taskMapper.updateById(any(InspectionTask.class))).thenReturn(1);

        Map<String, Object> body = Map.of(
                "taskId", 301L,
                "remark", "提交点检",
                "repairMode", 0,
                "items", List.of(Map.of(
                        "itemId", 11L,
                        "itemName", "机房温度",
                        "resultType", "text",
                        "result", 0,
                        "textResult", "23℃",
                        "remark", ""
                ))
        );

        Map<String, Object> result = service.submitTask(body);

        ArgumentCaptor<InspectionTask> captor = ArgumentCaptor.forClass(InspectionTask.class);
        verify(taskMapper).updateById(captor.capture());
        InspectionTask updated = captor.getValue();
        assertEquals(2, updated.getStatus());
        assertEquals("processing", updated.getApprovalStatus());
        assertEquals(901L, updated.getApprovalBillId());
        assertEquals(1, updated.getAttemptNo());
        assertTrue(Boolean.TRUE.equals(result.get("approvalStarted")));
        assertEquals(901L, result.get("approvalBillId"));
    }

    @Test
    void submitWithoutPublishedFlowStillCompletesTask() {
        InspectionTask task = new InspectionTask();
        task.setId(302L);
        task.setTenantId(18L);
        task.setTaskNo("INSP20261118002");
        task.setStatus(1);
        task.setPlanDate(LocalDate.now());
        LoginUser loginUser = (LoginUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        loginUser.setIsSuperAdmin(true);
        when(taskMapper.selectById(302L)).thenReturn(task);
        when(recordMapper.insert(any(InspectionRecord.class))).thenReturn(1);
        when(workflowService.hasPublishedFlow("inspection_task_confirm_t18", 18L)).thenReturn(false);
        when(taskMapper.updateById(any(InspectionTask.class))).thenReturn(1);

        Map<String, Object> body = Map.of(
                "taskId", 302L,
                "repairMode", 0,
                "items", List.of(Map.of("itemId", 12L, "itemName", "UPS", "result", 0))
        );

        Map<String, Object> result = service.submitTask(body);

        ArgumentCaptor<InspectionTask> captor = ArgumentCaptor.forClass(InspectionTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
        assertEquals(Boolean.FALSE, result.get("approvalStarted"));
    }
}
