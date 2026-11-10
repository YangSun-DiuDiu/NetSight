package com.netsight.modules.alert.service;

import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.mapper.EventRecordMapper;
import com.netsight.modules.system.mapper.SysTenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventCenterServiceReceiveEventTest {

    @Test
    void asyncProcessRunsAfterTransactionCommit() {
        EventRecordMapper eventMapper = mock(EventRecordMapper.class);
        NotificationRuleService ruleService = mock(NotificationRuleService.class);
        NotificationChannelService channelService = mock(NotificationChannelService.class);
        NotificationLogService logService = mock(NotificationLogService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        NotificationContactService contactService = mock(NotificationContactService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EventCenterService> selfProvider = mock(ObjectProvider.class);
        EventCenterService self = mock(EventCenterService.class);
        when(selfProvider.getObject()).thenReturn(self);

        EventCenterService service = new EventCenterService(eventMapper, ruleService, channelService,
                logService, redisTemplate, eventPublisher, objectMapper, tenantMapper, contactService, selfProvider);

        EventRecord event = new EventRecord();
        event.setTenantId(7L);
        event.setEventType("device_offline");
        event.setBizId("WXVERIFY-RACE");
        event.setId(99L);
        doReturn(1).when(eventMapper).insert(event);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.receiveEvent(event);

            // 事务提交前不得触发异步处理，否则异步线程可能读不到未提交的事件
            verify(self, never()).asyncProcess(anyLong());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(self).asyncProcess(99L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
