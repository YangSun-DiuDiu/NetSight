package com.netsight.modules.workorder.listener;

import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import com.netsight.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 工单完工验收审批结果监听器
 *
 * 监听 WorkflowApprovalEvent（work_order_accept），把审批结果同步回工单状态：
 * 通过 → 已完成；驳回 → 回到维修中，允许维修人员重新提交完工。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderAcceptanceListener {

    private final WorkOrderService workOrderService;

    @EventListener
    public void onApproval(WorkflowApprovalEvent event) {
        if (!WorkOrderService.BIZ_WORKORDER_ACCEPT.equals(event.getBizType())) {
            return;
        }
        try {
            workOrderService.onAcceptance(event.getBizId(), event.isPass(), event.getComment());
        } catch (Exception e) {
            // 验收结果同步失败不阻断审批主流程，记录后由审批单状态兜底
            log.error("工单验收结果同步异常 bizId={}: {}", event.getBizId(), e.getMessage());
        }
    }
}
