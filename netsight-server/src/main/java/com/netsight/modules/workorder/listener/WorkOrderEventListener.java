package com.netsight.modules.workorder.listener;

import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.event.AlertEvent;
import com.netsight.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 工单事件监听器。
 * 只负责把事件中心发布的 AlertEvent 路由到 WorkOrderService 的对应方法：
 *   - device_offline / device_line_abnormal → 自动建单
 *   - device_recovered                       → 自动归档
 * 事务与业务逻辑全部在 WorkOrderService 内，本类不写库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkOrderEventListener {

    private final WorkOrderService workOrderService;

    @EventListener
    public void onAlertEvent(AlertEvent alertEvent) {
        EventRecord event = alertEvent.getEvent();
        if (event == null) {
            return;
        }
        try {
            switch (event.getEventType()) {
                case "device_offline", "device_line_abnormal" -> workOrderService.autoCreateFromEvent(event);
                case "device_recovered" -> workOrderService.handleRecover(event);
                default -> { /* 其他事件不联动工单 */ }
            }
        } catch (Exception e) {
            log.error("告警事件[{}]工单联动异常: {}", alertEvent.getEventId(), e.getMessage(), e);
        }
    }
}
