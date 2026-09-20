package com.netsight.modules.alert.event;

import com.netsight.modules.alert.entity.EventRecord;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 告警事件发布对象（Spring 事件，业务模块订阅实现联动）
 * 用于解耦：事件中心发布告警事实 → 工单模块监听自动生成联系单 / 设备恢复自动归档
 */
@Getter
@RequiredArgsConstructor
@Slf4j
public class AlertEvent {

    /** 告警事件（已入库） */
    private final EventRecord event;

    /** 事件ID */
    public Long getEventId() {
        return event.getId();
    }

    /** 事件类型 */
    public String getEventType() {
        return event.getEventType();
    }
}
