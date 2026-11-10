package com.netsight.modules.alert.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock
    private NotificationTemplateMapper templateMapper;

    private NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService(templateMapper, JsonMapper.builder().build());
    }

    private NotificationTemplate wechatTemplate(String content) {
        NotificationTemplate template = new NotificationTemplate();
        template.setTenantId(7L);
        template.setChannelType("wechat");
        template.setChannelId(7L);
        template.setTemplateCode("tpl_device_offline");
        template.setContent(content);
        return template;
    }

    @Test
    void rejectsInvalidWechatTemplateJson() {
        NotificationTemplate template = wechatTemplate("not-json");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addTemplate(template));

        assertEquals("微信模板内容必须是合法 JSON", exception.getMessage());
    }

    @Test
    void rejectsBlankWechatTemplateContent() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addTemplate(wechatTemplate("")));

        assertEquals("微信模板内容不能为空", exception.getMessage());
    }

    @Test
    void rejectsWechatTemplateWithoutFields() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addTemplate(wechatTemplate("{\"templateId\":\"tpl\"}")));

        assertEquals("微信模板字段不能为空", exception.getMessage());
    }

    @Test
    void rejectsIllegalWechatFieldKey() {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addTemplate(wechatTemplate(
                        "{\"fields\":[{\"key\":\"thing-2\",\"value\":\"{{device_name}}\"}]}")));

        assertEquals("微信模板字段名不合法: thing-2", exception.getMessage());
    }

    @Test
    void acceptsValidWechatDescriptor() {
        NotificationTemplate template = wechatTemplate(
                "{\"templateId\":\"tpl\",\"url\":\"http://www.netsightplus.cn/m/orders\","
                        + "\"fields\":[{\"key\":\"thing2\",\"value\":\"{{device_name}}\",\"default\":\"未知设备\"}]}");
        when(templateMapper.selectCount(any())).thenReturn(0L);

        service.addTemplate(template);

        verify(templateMapper, times(1)).insert(template);
    }
}
