package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.netsight.modules.alert.dto.NotifyContactSyncResult;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.mapper.NotificationContactMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationContactServiceTest {

    @Mock private NotificationContactMapper contactMapper;

    private NotificationContactService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationContact.class);
        service = new NotificationContactService(contactMapper);
    }

    private NotificationContact contact(Long id, String name, String mobile, Integer status) {
        NotificationContact contact = new NotificationContact();
        contact.setId(id);
        contact.setTenantId(14L);
        contact.setName(name);
        contact.setMobile(mobile);
        contact.setStatus(status);
        return contact;
    }

    @Test
    void createsEnabledContactWhenNoneExists() {
        when(contactMapper.selectList(any())).thenReturn(List.of());
        when(contactMapper.insert(any(NotificationContact.class))).thenAnswer(invocation -> {
            NotificationContact inserted = invocation.getArgument(0);
            inserted.setId(5L);
            return 1;
        });

        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "19900000009", "admin");

        assertEquals(NotifyContactSyncResult.Action.CREATED, result.getAction());
        assertEquals(5L, result.getContactId());
        ArgumentCaptor<NotificationContact> captor = ArgumentCaptor.forClass(NotificationContact.class);
        verify(contactMapper).insert(captor.capture());
        NotificationContact inserted = captor.getValue();
        assertEquals(14L, inserted.getTenantId());
        assertEquals("张三", inserted.getName());
        assertEquals("19900000009", inserted.getMobile());
        assertEquals(1, inserted.getStatus());
        assertEquals("admin", inserted.getCreateBy());
        assertEquals("维修人员创建时同步", inserted.getRemark());
    }

    @Test
    void reusesExistingEnabledContactWithoutDuplicateInsert() {
        NotificationContact existing = contact(5L, "张三", "19900000009", 1);
        when(contactMapper.selectList(any())).thenReturn(List.of(existing));

        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "19900000009", "admin");

        assertEquals(NotifyContactSyncResult.Action.EXISTING, result.getAction());
        assertEquals(5L, result.getContactId());
        verify(contactMapper, never()).insert(any(NotificationContact.class));
        verify(contactMapper, never()).updateById(any(NotificationContact.class));
    }

    @Test
    void enablesExistingDisabledContactWithoutDuplicateInsert() {
        NotificationContact existing = contact(5L, "张三", "19900000009", 0);
        when(contactMapper.selectList(any())).thenReturn(List.of(existing));
        when(contactMapper.updateById(any(NotificationContact.class))).thenReturn(1);

        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "19900000009", "admin");

        assertEquals(NotifyContactSyncResult.Action.ENABLED, result.getAction());
        assertEquals(5L, result.getContactId());
        ArgumentCaptor<NotificationContact> captor = ArgumentCaptor.forClass(NotificationContact.class);
        verify(contactMapper).updateById(captor.capture());
        assertEquals(5L, captor.getValue().getId());
        assertEquals(1, captor.getValue().getStatus());
        verify(contactMapper, never()).insert(any(NotificationContact.class));
    }

    @Test
    void returnsAmbiguousWhenMultipleEnabledContactsExist() {
        when(contactMapper.selectList(any())).thenReturn(List.of(
                contact(5L, "张三", "19900000009", 1),
                contact(6L, "张三", "19900000009", 1)));

        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "19900000009", "admin");

        assertEquals(NotifyContactSyncResult.Action.AMBIGUOUS, result.getAction());
        assertNull(result.getContactId());
        verify(contactMapper, never()).insert(any(NotificationContact.class));
        verify(contactMapper, never()).updateById(any(NotificationContact.class));
    }

    @Test
    void returnsAmbiguousWhenMultipleDisabledContactsExist() {
        when(contactMapper.selectList(any())).thenReturn(List.of(
                contact(5L, "张三", "19900000009", 0),
                contact(6L, "张三", "19900000009", 0)));

        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "19900000009", "admin");

        assertEquals(NotifyContactSyncResult.Action.AMBIGUOUS, result.getAction());
        verify(contactMapper, never()).insert(any(NotificationContact.class));
        verify(contactMapper, never()).updateById(any(NotificationContact.class));
    }

    @Test
    void returnsNoPhoneWhenMobileIsBlank() {
        NotifyContactSyncResult result = service.ensureContactForRepairer(
                14L, "张三", "  ", "admin");

        assertEquals(NotifyContactSyncResult.Action.NO_PHONE, result.getAction());
        verify(contactMapper, never()).selectList(any());
        verify(contactMapper, never()).insert(any(NotificationContact.class));
    }
}
