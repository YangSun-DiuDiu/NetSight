package com.netsight.modules.alert.service;

import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.LoginUser;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    private static final String VALID_WECHAT_CONTENT =
            "{\"templateId\":\"tpl\",\"url\":\"http://www.netsightplus.cn/m/orders\","
                    + "\"fields\":[{\"key\":\"thing2\",\"value\":\"{{device_name}}\",\"default\":\"未知设备\"}]}";

    @Mock
    private NotificationTemplateMapper templateMapper;

    @Mock
    private NotifyChannelService notifyChannelService;

    private NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationTemplateService(
                templateMapper, JsonMapper.builder().build(), notifyChannelService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
        NotificationTemplate template = wechatTemplate(VALID_WECHAT_CONTENT);
        when(templateMapper.selectCount(any())).thenReturn(0L);

        service.addTemplate(template);

        verify(templateMapper, times(1)).insert(template);
    }

    @Test
    void rejectsChannelOfAnotherTenantOnAdd() {
        NotificationTemplate template = wechatTemplate(VALID_WECHAT_CONTENT);
        doThrow(new ServiceException("绑定的通道不存在、未启用或不属于当前租户"))
                .when(notifyChannelService).assertChannelUsable(7L, 7L);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.addTemplate(template));

        assertEquals("绑定的通道不存在、未启用或不属于当前租户", exception.getMessage());
    }

    @Test
    void updateTemplateLocksExistingTenantBeforeChannelValidation() {
        LoginUser user = new LoginUser();
        user.setTenantId(7L);
        user.setIsSuperAdmin(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        NotificationTemplate exist = new NotificationTemplate();
        exist.setId(43L);
        exist.setTenantId(7L);
        exist.setChannelType("wechat");
        exist.setChannelId(7L);
        exist.setTemplateCode("tpl_device_offline");
        when(templateMapper.selectById(43L)).thenReturn(exist);

        NotificationTemplate update = new NotificationTemplate();
        update.setId(43L);
        update.setTenantId(1L);
        update.setChannelType("wechat");
        update.setChannelId(20L);
        update.setContent(VALID_WECHAT_CONTENT);

        service.updateTemplate(update);

        assertEquals(7L, update.getTenantId());
        verify(notifyChannelService).assertChannelUsable(20L, 7L);
        verify(templateMapper).updateById(update);
    }
}
