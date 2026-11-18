package com.netsight.modules.inspection.listener;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 点检任务确认审批结果回写测试。
 */
@ExtendWith(MockitoExtension.class)
class InspectionApprovalListenerTest {

    @Mock private InspectionTaskMapper taskMapper;

    private InspectionApprovalListener listener;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), InspectionTask.class);
        listener = new InspectionApprovalListener(taskMapper);
    }

    private InspectionTask task() {
        InspectionTask task = new InspectionTask();
        task.setId(301L);
        task.setTenantId(17L);
        task.setTaskNo("INSP20261118001");
        task.setStatus(2);
        task.setApprovalStatus("processing");
        task.setAttemptNo(1);
        return task;
    }

    @Test
    void approvedEventMarksTaskApproved() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        when(taskMapper.updateById(any(InspectionTask.class))).thenReturn(1);

        listener.onApproval(new WorkflowApprovalEvent(
                "inspection_task_confirm", "INSP20261118001", true, "确认通过", "approved", 901L, 17L));

        ArgumentCaptor<InspectionTask> captor = ArgumentCaptor.forClass(InspectionTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals("approved", captor.getValue().getApprovalStatus());
        assertEquals(2, captor.getValue().getStatus());
        assertEquals(901L, captor.getValue().getApprovalBillId());
    }

    @Test
    void rejectedEventReturnsTaskToPendingAndIncrementsAttempt() {
        when(taskMapper.selectOne(any())).thenReturn(task());
        when(taskMapper.updateById(any(InspectionTask.class))).thenReturn(1);

        listener.onApproval(new WorkflowApprovalEvent(
                "inspection_task_confirm", "INSP20261118001", false, "照片不清晰，请重检", "rejected", 901L, 17L));

        ArgumentCaptor<InspectionTask> captor = ArgumentCaptor.forClass(InspectionTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals("rejected", captor.getValue().getApprovalStatus());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals(2, captor.getValue().getAttemptNo());
        assertEquals(901L, captor.getValue().getApprovalBillId());
    }

    @Test
    void intermediateProcessingEventDoesNotWriteTask() {
        listener.onApproval(new WorkflowApprovalEvent(
                "inspection_task_confirm", "INSP20261118001", true, "节点通过", "processing", 901L, 17L));

        verify(taskMapper, never()).updateById(any(InspectionTask.class));
    }

    @Test
    void ignoresOtherBizTypes() {
        listener.onApproval(new WorkflowApprovalEvent(
                "out_stock", "OUT001", true, "通过", "approved", 902L, 17L));

        verify(taskMapper, never()).selectOne(any());
        verify(taskMapper, never()).updateById(any(InspectionTask.class));
    }
}
