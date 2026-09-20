package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.mapper.NotificationRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 通知路由规则服务（自动发送策略）
 * 管理员按业务场景配置：事件类型 → 触发条件 → 接收人策略 → 通道列表 → 模板
 * 事件中心按 event_type 匹配启用的规则完成路由
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRuleService {

    private final NotificationRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    /**
     * 分页查询规则（租户隔离）
     */
    public PageResult<NotificationRule> pageRule(long pageNum, long pageSize, String ruleName, String eventType, Integer enabled) {
        Page<NotificationRule> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<NotificationRule> wrapper = new LambdaQueryWrapper<NotificationRule>()
                .like(StringUtils.hasText(ruleName), NotificationRule::getRuleName, ruleName)
                .eq(StringUtils.hasText(eventType), NotificationRule::getEventType, eventType)
                .eq(enabled != null, NotificationRule::getEnabled, enabled);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationRule::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(NotificationRule::getId);
        Page<NotificationRule> result = ruleMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 新增规则（默认归当前租户）
     */
    public void addRule(NotificationRule rule) {
        if (rule.getTenantId() == null) {
            rule.setTenantId(SecurityUtils.getTenantId());
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
        ruleMapper.insert(rule);
    }

    /**
     * 修改规则（仅本租户；禁止篡改租户归属，防跨租户覆盖）
     */
    public void updateRule(NotificationRule rule) {
        NotificationRule exist = ruleMapper.selectById(rule.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "规则不存在");
        }
        checkTenantPermission(exist.getTenantId());
        // 【加固】强制租户一致：updateById 时即使请求体带 tenantId 也不允许改归属
        rule.setTenantId(exist.getTenantId());
        ruleMapper.updateById(rule);
    }

    /**
     * 删除规则（仅本租户）
     */
    public void deleteRule(Long id) {
        NotificationRule exist = ruleMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "规则不存在");
        }
        checkTenantPermission(exist.getTenantId());
        ruleMapper.deleteById(id);
    }

    /**
     * 查询租户下启用的规则列表（事件中心匹配用，按规则ID倒序，命中最新配置）
     */
    public List<NotificationRule> listEnabledRules(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<NotificationRule>()
                .eq(NotificationRule::getEnabled, 1)
                .eq(NotificationRule::getTenantId, tenantId)
                .orderByDesc(NotificationRule::getId));
    }

    /**
     * 解析通道列表 JSON → List<Long>（渠道实例 ID）
     * 兼容旧数据：字符串元素尝试按数字解析
     */
    public List<Long> parseChannels(NotificationRule rule) {
        try {
            List<Object> raw = objectMapper.readValue(rule.getChannelsJson(), new TypeReference<List<Object>>() {});
            List<Long> ids = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Number n) ids.add(n.longValue());
                else if (o instanceof String s && s.matches("\\d+")) ids.add(Long.valueOf(s));
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析接收人策略 JSON → 手机号列表（旧格式兼容：{"type":"fixed","receivers":["手机号"]}）
     */
    public List<String> parseReceivers(NotificationRule rule) {
        try {
            Map<String, Object> strategy = objectMapper.readValue(rule.getReceiverStrategyJson(),
                    new TypeReference<>() {
                    });
            Object receivers = strategy.get("receivers");
            if (receivers instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析接收人策略 JSON → 通知联系人 ID 列表（新格式 {"type":"fixed","contactIds":[1,2]}）
     * 发送时按 contactIds 查联系人，再按通道分别解析 mobile/openid。
     */
    public List<Long> parseContactIds(NotificationRule rule) {
        if (!StringUtils.hasText(rule.getReceiverStrategyJson())) {
            return List.of();
        }
        try {
            Map<String, Object> strategy = objectMapper.readValue(rule.getReceiverStrategyJson(),
                    new TypeReference<>() {
                    });
            Object ids = strategy.get("contactIds");
            if (ids instanceof List<?> list) {
                List<Long> result = new java.util.ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Number n) {
                        result.add(n.longValue());
                    } else {
                        try {
                            result.add(Long.valueOf(String.valueOf(o)));
                        } catch (Exception ignored) {
                        }
                    }
                }
                return result;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 解析触发条件 JSON → Map（空条件 = 全部匹配）
     */
    public Map<String, Object> parseCondition(NotificationRule rule) {
        if (!StringUtils.hasText(rule.getConditionJson())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rule.getConditionJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 数据隔离校验（targetTenantId 为 null 属脏数据，一并拦截防 NPE/越权）
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin()
                && (targetTenantId == null || !targetTenantId.equals(SecurityUtils.getTenantId()))) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }
}
