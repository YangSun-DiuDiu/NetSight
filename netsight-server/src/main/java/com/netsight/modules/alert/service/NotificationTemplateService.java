package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    private final NotificationTemplateMapper templateMapper;

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
     * 新增模板（编码租户内唯一）
     */
    public void addTemplate(NotificationTemplate template) {
        if (template.getTenantId() == null) {
            template.setTenantId(SecurityUtils.getTenantId());
        }
        Long count = templateMapper.selectCount(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, template.getTemplateCode())
                .eq(NotificationTemplate::getTenantId, template.getTenantId()));
        if (count > 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板编码已存在");
        }
        templateMapper.insert(template);
    }

    /**
     * 修改模板（仅本租户，超管不限）
     */
    public void updateTemplate(NotificationTemplate template) {
        NotificationTemplate exist = templateMapper.selectById(template.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "模板不存在");
        }
        checkTenantPermission(exist.getTenantId());
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
     * 数据隔离校验：非超管不能操作其他租户模板
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }
}
