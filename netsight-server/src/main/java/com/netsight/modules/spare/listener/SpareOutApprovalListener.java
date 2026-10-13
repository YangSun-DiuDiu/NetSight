package com.netsight.modules.spare.listener;

import com.netsight.modules.spare.service.SpareOutService;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 备件出库/入库申请审批结果监听器
 *
 * 监听 WorkflowApprovalEvent（out_stock / in_stock），同步 spare_out_request 审批状态（审批存档）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpareOutApprovalListener {

    private final SpareOutService spareOutService;

    @EventListener
    public void onApproval(WorkflowApprovalEvent event) {
        try {
            spareOutService.onApproval(event.getBizType(), event.getBizId(),
                    event.isPass(), event.getComment());
        } catch (Exception e) {
            // 审批存档失败不影响审批主流程，仅记录
            log.error("备件审批存档异常 bizType={} bizId={}: {}", event.getBizType(), event.getBizId(), e.getMessage());
        }
    }
}
