package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.channel.SendRequest;
import com.netsight.modules.alert.channel.SendResult;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationLogMapper;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板组扇出测试：验证短信失败不会再自动补发企业微信（方案A：删除降级分支）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationChannelServiceTest {

    private static final Long TENANT_ID = 14L;

    @Mock private ChannelRegistry channelRegistry;
    @Mock private NotificationTemplateMapper templateMapper;
    @Mock private NotificationLogMapper logMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private NotificationContactService contactService;
    @Mock private NotifyChannelService notifyChannelService;
    @Mock private NotificationChannelSender smsSender;
    @Mock private NotificationChannelSender wechatWorkSender;

    private NotificationChannelService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), NotificationTemplate.class);
        service = new NotificationChannelService(
                channelRegistry, templateMapper, logMapper, objectMapper,
                contactService, notifyChannelService);
        ReflectionTestUtils.setField(service, "retryIntervals", "0");
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");
        when(logMapper.insert(any(NotificationLog.class))).thenReturn(1);
    }

    @Test
    void smsFailureDoesNotFallbackWhenNoWechatWorkTemplateInGroup() throws Exception {
        NotificationTemplate main = template(1L, "tpl_device_offline", 100L, "sms", "sms {{device_name}}");
        NotificationTemplate sms = template(10L, "tpl_device_offline", 100L, "sms", "sms {{device_name}}");
        when(templateMapper.selectOne(any())).thenReturn(main);
        when(templateMapper.selectList(any())).thenReturn(List.of(sms));
        when(notifyChannelService.resolveChannels(anyList(), eq(TENANT_ID)))
                .thenAnswer(invocation -> List.of(channel(100L, "sms")));
        when(channelRegistry.get("sms")).thenReturn(smsSender);
        when(smsSender.getChannelType()).thenReturn("sms");
        when(smsSender.send(any(SendRequest.class))).thenReturn(failResult());

        List<NotificationLog> logs = service.dispatch(event(), rule(), contacts(), null,
                Map.of("device_name", "核心交换机"), null);

        assertEquals(1, logs.size());
        assertEquals(0, logs.get(0).getSuccess());
        verify(channelRegistry, never()).get("wechat_work");
        verify(logMapper, times(1)).insert(any(NotificationLog.class));
    }

    @Test
    void wechatWorkInGroupIsSentNormallyAndNotDuplicatedByFallback() throws Exception {
        NotificationTemplate main = template(1L, "tpl_device_offline", 100L, "sms", "sms {{device_name}}");
        NotificationTemplate sms = template(10L, "tpl_device_offline", 100L, "sms", "sms {{device_name}}");
        NotificationTemplate wx = template(11L, "tpl_device_offline", 200L, "wechat_work", "wx {{device_name}}");
        when(templateMapper.selectOne(any())).thenReturn(main);
        when(templateMapper.selectList(any())).thenReturn(List.of(sms, wx));
        when(notifyChannelService.resolveChannels(anyList(), eq(TENANT_ID)))
                .thenAnswer(invocation -> {
                    List<Long> ids = invocation.getArgument(0);
                    Long id = ids.get(0);
                    return List.of(channel(id, id == 100L ? "sms" : "wechat_work"));
                });
        when(channelRegistry.get("sms")).thenReturn(smsSender);
        when(channelRegistry.get("wechat_work")).thenReturn(wechatWorkSender);
        when(smsSender.getChannelType()).thenReturn("sms");
        when(smsSender.send(any(SendRequest.class))).thenReturn(failResult());
        when(wechatWorkSender.send(any(SendRequest.class))).thenReturn(successResult());

        List<NotificationLog> logs = service.dispatch(event(), rule(), contacts(), null,
                Map.of("device_name", "核心交换机"), null);

        assertEquals(2, logs.size());
        assertEquals(0, logs.get(0).getSuccess());
        assertEquals(1, logs.get(1).getSuccess());
        verify(smsSender, times(1)).send(any(SendRequest.class));
        verify(wechatWorkSender, times(1)).send(any(SendRequest.class));
        verify(logMapper, times(2)).insert(any(NotificationLog.class));
    }

    private NotificationTemplate template(Long id, String code, Long channelId, String type, String content) {
        NotificationTemplate template = new NotificationTemplate();
        template.setId(id);
        template.setTenantId(TENANT_ID);
        template.setTemplateCode(code);
        template.setTemplateName(code);
        template.setChannelId(channelId);
        template.setChannelType(type);
        template.setContent(content);
        template.setEnabled(1);
        return template;
    }

    private Map<String, Object> channel(Long channelId, String channelType) {
        return Map.of(
                "channelId", channelId,
                "channelType", channelType,
                "config", Map.of());
    }

    private EventRecord event() {
        EventRecord event = new EventRecord();
        event.setId(1L);
        event.setTenantId(TENANT_ID);
        event.setEventType("device_offline");
        event.setBizId("biz-1");
        return event;
    }

    private NotificationRule rule() {
        NotificationRule rule = new NotificationRule();
        rule.setId(1L);
        rule.setTenantId(TENANT_ID);
        rule.setRuleName("离线告警规则");
        rule.setTemplateId(1L);
        return rule;
    }

    private List<NotificationContact> contacts() {
        NotificationContact contact = new NotificationContact();
        contact.setId(1L);
        contact.setTenantId(TENANT_ID);
        contact.setName("张三");
        contact.setMobile("19900000009");
        contact.setStatus(1);
        return List.of(contact);
    }

    private SendResult successResult() {
        return SendResult.builder().success(true).costTime(0).build();
    }

    private SendResult failResult() {
        return SendResult.builder().success(false).retryable(false).errorMsg("mock failure").costTime(0).build();
    }
}
