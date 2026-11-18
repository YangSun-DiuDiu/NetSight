package com.netsight.modules.inspection.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.inspection.service.InspectionService;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点检任务确认审批结果监听器。
 *
 * 通过：任务保持已完成，审批状态置 approved；
 * 驳回：任务回到待执行，审批状态置 rejected，轮次 +1，允许重新执行后再次提交审批。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionApprovalListener {

    private final InspectionTaskMapper taskMapper;

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onApproval(WorkflowApprovalEvent event) {
        if (!InspectionService.BIZ_INSPECTION_CONFIRM.equals(event.getBizType())) {
            return;
        }
        // 中间节点通过时状态仍为 processing，不写业务表，等待最终节点
        if (event.isPass() && !"approved".equals(event.getStatus())) {
            return;
        }
        try {
            InspectionTask task = findTask(event);
            if (task == null) {
                log.warn("[点检审批] 未找到任务 bizId={} tenantId={}", event.getBizId(), event.getTenantId());
                return;
            }
            InspectionTask update = new InspectionTask();
            update.setId(task.getId());
            if (event.getBillId() != null) {
                update.setApprovalBillId(event.getBillId());
            }
            if (event.isPass()) {
                update.setApprovalStatus("approved");
                update.setStatus(2);
            } else {
                update.setApprovalStatus("rejected");
                // 驳回后回到待执行，运维人员需重新开始并执行点检
                update.setStatus(0);
                int attemptNo = task.getAttemptNo() == null ? 1 : task.getAttemptNo();
                update.setAttemptNo(attemptNo + 1);
            }
            taskMapper.updateById(update);
            log.info("[点检审批] 回写完成 taskNo={} pass={} billId={} status={}",
                    task.getTaskNo(), event.isPass(), event.getBillId(), update.getApprovalStatus());
        } catch (Exception e) {
            // 审批结果回写失败不阻断审批主流程，由审批单状态兜底
            log.error("[点检审批] 回写异常 bizId={} billId={}: {}",
                    event.getBizId(), event.getBillId(), e.getMessage());
        }
    }

    private InspectionTask findTask(WorkflowApprovalEvent event) {
        LambdaQueryWrapper<InspectionTask> wrapper = new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getTaskNo, event.getBizId())
                .eq(event.getTenantId() != null, InspectionTask::getTenantId, event.getTenantId())
                .eq(InspectionTask::getDelFlag, 0)
                .last("LIMIT 1");
        return taskMapper.selectOne(wrapper);
    }
}
