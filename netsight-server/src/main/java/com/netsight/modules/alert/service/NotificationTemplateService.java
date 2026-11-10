package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.channel.WechatTemplatePayload;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息模板服务
 * 模板 CRUD（租户隔离）+ 模板内容渲染（{{var}} 占位符替换）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+?)\\s*}}");
    private static final int MAX_WECHAT_CONTENT_BYTES = 8 * 1024;
    private static final int MAX_WECHAT_FIELDS = 30;
    private static final Pattern WECHAT_FIELD_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final NotificationTemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询模板（租户隔离）
     */
    public PageResult<NotificationTemplate> pageTemplate(long pageNum, long pageSize, String templateName, String channelType) {
        Page<NotificationTemplate> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<NotificationTemplate> wrapper = new LambdaQueryWrapper<NotificationTemplate>()
                .like(StringUtils.hasText(templateName), NotificationTemplate::getTemplateName, templateName)
                .eq(StringUtils.hasText(channelType), NotificationTemplate::getChannelType, channelType);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationTemplate::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(NotificationTemplate::getId);
        Page<NotificationTemplate> result = templateMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 模板下拉（不跨租户）
     */
    public List<NotificationTemplate> listOptions(String channelType) {
        LambdaQueryWrapper<NotificationTemplate> wrapper = new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getEnabled, 1);
        if (StringUtils.hasText(channelType)) {
            wrapper.eq(NotificationTemplate::getChannelType, channelType);
        }
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationTemplate::getTenantId, SecurityUtils.getTenantId());
        }
        return templateMapper.selectList(wrapper);
    }

    /**
     * 新增模板。
     * 设计：templateCode 是"模板组编码"——同 code 下允许挂多个通道（短信/微信/PushPlus），
     * 由 dispatchByTemplateGroup 遍历同 code 全部启用模板实现多通道下发。
     * 因此唯一性约束不是 (tenantId, templateCode)，而是 (tenantId, templateCode, channelId)：
     * 同一个通道实例在同一个模板组里只能绑一次，避免重复发送。
     */
    public void addTemplate(NotificationTemplate template) {
        if (template.getChannelId() == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板必须绑定通道实例（channelId 不能为空）");
        }
        validateWechatContent(template.getChannelType(), template.getContent());
        if (template.getTenantId() == null) {
            template.setTenantId(SecurityUtils.getTenantId());
        }
        Long count = templateMapper.selectCount(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, template.getTemplateCode())
                .eq(NotificationTemplate::getTenantId, template.getTenantId())
                .eq(NotificationTemplate::getChannelId, template.getChannelId()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(),
                    "该通道实例已绑定到模板组[" + template.getTemplateCode() + "]，请勿重复添加（同组可挂多个不同通道）");
        }
        templateMapper.insert(template);
    }

    /**
     * 修改模板（仅本租户，超管不限；必须绑定通道实例，无类型兜底）
     */
    public void updateTemplate(NotificationTemplate template) {
        NotificationTemplate exist = templateMapper.selectById(template.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板不存在");
        }
        checkTenantPermission(exist.getTenantId());
        if (template.getChannelId() == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板必须绑定通道实例（channelId 不能为空）");
        }
        String channelType = StringUtils.hasText(template.getChannelType())
                ? template.getChannelType() : exist.getChannelType();
        validateWechatContent(channelType, template.getContent());
        templateMapper.updateById(template);
    }

    /**
     * 删除模板（仅本租户，超管不限）
     */
    public void deleteTemplate(Long id) {
        NotificationTemplate exist = templateMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板不存在");
        }
        checkTenantPermission(exist.getTenantId());
        templateMapper.deleteById(id);
    }

    /**
     * 模板内容渲染：{{device_name}} → 变量值
     */
    public static String render(String content, Map<String, Object> vars) {
        if (content == null || vars == null || vars.isEmpty()) {
            return content;
        }
        Matcher matcher = VAR_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = vars.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 微信模板 content 必须是描述符 JSON：templateId/url/fields[{key,value,default}]。
     * 空 content 对微信通道视为错误（历史 115 空 content 由数据脚本修正）。
     */
    private void validateWechatContent(String channelType, String content) {
        if (!"wechat".equals(channelType)) {
            return;
        }
        if (!StringUtils.hasText(content)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板内容不能为空");
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_WECHAT_CONTENT_BYTES) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板内容不能超过 8KB");
        }
        try {
            WechatTemplatePayload.Descriptor descriptor =
                    objectMapper.readValue(content, WechatTemplatePayload.Descriptor.class);
            if (descriptor.getFields() == null || descriptor.getFields().isEmpty()) {
                throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段不能为空");
            }
            if (descriptor.getFields().size() > MAX_WECHAT_FIELDS) {
                throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板字段过多");
            }
            for (WechatTemplatePayload.Field field : descriptor.getFields()) {
                if (field == null || !StringUtils.hasText(field.getKey())
                        || !WECHAT_FIELD_KEY.matcher(field.getKey()).matches()) {
                    throw new ServiceException(ResultCode.PARAM_ERROR.getCode(),
                            "微信模板字段名不合法: " + (field == null ? null : field.getKey()));
                }
                if (!StringUtils.hasText(field.getValue())) {
                    throw new ServiceException(ResultCode.PARAM_ERROR.getCode(),
                            "微信模板字段变量不能为空: " + field.getKey());
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "微信模板内容必须是合法 JSON");
        }
    }

    /**
     * 数据隔离校验：非超管不能操作其他租户模板
     */    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }
}
