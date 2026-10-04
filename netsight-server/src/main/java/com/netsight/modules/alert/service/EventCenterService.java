package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.config.PushWebSocketHandler;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.event.AlertEvent;
import com.netsight.modules.alert.mapper.EventRecordMapper;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件中心（事件驱动 + 规则路由）
 * 职责：事件统一接入入库 → 按 event_type 匹配路由规则 → 去重限流 → 路由到通知通道中心
 * 业务、事件、通道三层解耦：业务只上报事件，事件中心不关心通道实现，通道中心不关心事件来源
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCenterService {

    private static final String DEDUP_KEY_PREFIX = "netsight:event:dedup:";

    /**
     * 去重窗口：同一 biz_id + event_type 在该窗口内只发送一次。
     * 此前为 5 分钟 —— 设备持续离线时 AlertManager 周期性重复推送，每 5 分钟重发一次相同内容，
     * 长时间累积触发 PushPlus 等第三方通道"相同内容/频率过快"风控（code=999）导致后续全部拒收。
     * 改为 24 小时：设备持续离线期间只通知一次；设备恢复事件到达时清除对应去重键，
     * 保证"恢复后再离线"能再次通知。
     */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final EventRecordMapper eventMapper;
    private final NotificationRuleService ruleService;
    private final NotificationChannelService channelService;
    private final NotificationLogService logService;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SysTenantMapper tenantMapper;
    private final NotificationContactService contactService;
    /**
     * 自身代理（@Async 走 Spring AOP 代理，同类内部调用 this.asyncProcess() 会绕过代理导致同步执行）：
     * receiveEvent 必须通过代理调用 asyncProcess，才能落到 eventExecutor 线程池异步处理，
     * 否则 /alert/push 会阻塞到通知发送完成（PushPlus 重试时可达 10s+，导致边缘网关请求超时重复补传）。
     */
    private final ObjectProvider<EventCenterService> selfProvider;

    /**
     * 事件统一接入（同步入库，异步处理，不阻塞业务）
     * webhook / 手动触发 / 业务模块均调用此入口
     * REQUIRES_NEW：从业务事务内调用时立即提交，确保异步处理能读到事件
     *
     * @param event 事件（tenantId 必须已赋值）
     * @return 事件ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long receiveEvent(EventRecord event) {
        if (event.getTenantId() == null) {
            event.setTenantId(1L);
        }
        if (!StringUtils.hasText(event.getEventType())) {
            throw new ServiceException(ResultCode.PARAM_ERROR);
        }
        if (event.getStatus() == null) {
            event.setStatus("pending");
        }
        if (event.getRetryCount() == null) {
            event.setRetryCount(0);
        }
        eventMapper.insert(event);
        Long eventId = event.getId();
        // 异步处理（通过 Spring 代理调用，确保 @Async 生效落到 eventExecutor 线程池，不阻塞调用方）
        selfProvider.getObject().asyncProcess(eventId);
        return eventId;
    }

    /**
     * 手动触发事件：管理员手动选择通道/联系人/内容发送
     * 不走规则匹配，直接按传入参数分发
     *
     * @param contactIds 通知联系人 ID（按通道分别解析；仅 pushplus 通道时可为空）
     */
    public Long manualEvent(Long tenantId, String eventType, String bizId, String content,
                            List<Long> contactIds, List<Long> channels) {
        EventRecord event = new EventRecord();
        event.setTenantId(tenantId);
        event.setEventType(eventType);
        event.setEventSource("manual");
        event.setSeverity("info");
        event.setBizId(bizId);
        event.setContent(content);
        event.setStatus("pending");
        event.setRetryCount(0);
        eventMapper.insert(event);

        // 手动发送不参与 24 小时去重（管理员主动补发/公告）
        dispatchManual(event, contactIds, channels, content);
        return event.getId();
    }

    /**
     * 异步处理事件：规则匹配 → 去重 → 路由分发 → 状态回写
     */
    @Async("eventExecutor")
    public void asyncProcess(Long eventId) {
        EventRecord event = eventMapper.selectById(eventId);
        if (event == null) {
            return;
        }
        try {
            // 0. 发布告警事件（业务模块联动：工单自动生成 / 设备恢复归档等）
            try {
                eventPublisher.publishEvent(new AlertEvent(event));
            } catch (Exception e) {
                log.error("发布告警事件异常 eventId={}: {}", eventId, e.getMessage());
            }

            // 0.5 设备恢复事件：清除该设备的离线/外线去重键，确保"恢复后再离线"能再次通知
            if ("device_recovered".equals(event.getEventType()) && StringUtils.hasText(event.getBizId())) {
                try {
                    redisTemplate.delete(DEDUP_KEY_PREFIX + event.getBizId() + ":device_offline");
                    redisTemplate.delete(DEDUP_KEY_PREFIX + event.getBizId() + ":device_line_abnormal");
                    log.info("事件[{}]设备恢复，已清除离线/外线去重键 bizId={}", eventId, event.getBizId());
                } catch (Exception e) {
                    log.warn("清除去重键失败 bizId={}: {}", event.getBizId(), e.getMessage());
                }
            }

            EventRecord update = new EventRecord();
            update.setId(eventId);
            update.setStatus("processing");
            eventMapper.updateById(update);

            // 1. 规则匹配（租户内启用规则，event_type 相同，condition 满足）
            List<NotificationRule> rules = ruleService.listEnabledRules(event.getTenantId());
            List<NotificationRule> matched = rules.stream()
                    .filter(r -> event.getEventType().equals(r.getEventType()))
                    .filter(r -> matchCondition(r, event))
                    .toList();
            if (matched.isEmpty()) {
                // 无匹配规则：事件入库但不发送，标记 sent（已受理）
                markEvent(eventId, "sent", null, null);
                log.info("事件[{}]{}无匹配路由规则，仅记录不发送", eventId, event.getEventType());
                return;
            }
            // 2. 取最新一条规则执行发送（按配置）
            NotificationRule rule = matched.get(0);
            // 解析接收人：按联系人 ID 查联系人（异步线程无租户上下文，显式带事件租户防跨租户），
            // 发送时由通道中心按通道分别解析 mobile/openid，pushplus 忽略接收人按租户 token 群发。
            List<Long> contactIds = ruleService.parseContactIds(rule);
            List<NotificationContact> contacts = contactService.resolveContacts(contactIds, event.getTenantId());
            // 模板即通道：自动通知由规则绑定的模板组决定通道，不再解析规则通道列表
            Map<String, Object> vars = buildVars(event);

            // 3. 去重限流：同一 biz_id + event_type 窗口内只发送一次
            boolean deduped = false;
            if (StringUtils.hasText(event.getBizId())) {
                String dedupKey = DEDUP_KEY_PREFIX + event.getBizId() + ":" + event.getEventType();
                Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
                deduped = first != null && !first;
            }
            if (deduped) {
                markEvent(eventId, "sent", rule.getId(), rule.getRuleName());
                log.info("事件[{}]命中去重（bizId={} 5分钟内已发送），跳过发送", eventId, event.getBizId());
                return;
            }

            // 4. 路由分发到通道中心（模板即通道：规则模板组决定通道）
            List<NotificationLog> logs = channelService.dispatch(event, rule, contacts, null, vars, null);

            // 5. 状态回写
            long failCount = logs.stream().filter(l -> l.getSuccess().equals(0)).count();
            String status = failCount == 0 ? "sent" : (failCount == logs.size() ? "failed" : "part_failed");
            markEvent(eventId, status, rule.getId(), rule.getRuleName());
            log.info("事件[{}]{}处理完成，发送日志{}条，失败{}条",
                    eventId, event.getEventType(), logs.size(), failCount);
            // 6. 实时推送：新告警事件广播（大屏/告警中心实时刷新）
            try {
                PushWebSocketHandler.broadcast("event", Map.of(
                        "eventId", eventId, "eventType", event.getEventType(),
                        "severity", event.getSeverity() == null ? "" : event.getSeverity(),
                        "deviceName", event.getDeviceName() == null ? "" : event.getDeviceName(),
                        "deviceIp", event.getDeviceIp() == null ? "" : event.getDeviceIp(),
                        "status", status, "content", event.getContent() == null ? "" : event.getContent()));
            } catch (Exception ignored) {
                // 推送失败不影响主流程
            }
        } catch (Exception e) {
            log.error("事件[{}]异步处理异常: {}", eventId, e.getMessage(), e);
            markEvent(eventId, "failed", null, null);
        }
    }

    /**
     * 手动发送分发（不参与去重，直接发送并记录）
     * 注意：pushplus 通道忽略接收人按租户 token 群发，故 contacts 为空也允许发送（仅 pushplus 场景）。
     */
    private void dispatchManual(EventRecord event, List<Long> contactIds, List<Long> channels, String content) {
        if (channels == null || channels.isEmpty()) {
            markEvent(event.getId(), "sent", null, null);
            return;
        }
        try {
            List<NotificationContact> contacts = contactService.resolveContacts(contactIds, event.getTenantId());
            Map<String, Object> vars = new HashMap<>();
            vars.put("content", content == null ? "" : content);
            List<NotificationLog> logs = channelService.dispatch(event, null, contacts, channels, vars, null);
            long failCount = logs.stream().filter(l -> l.getSuccess().equals(0)).count();
            String status = failCount == 0 ? "sent" : (failCount == logs.size() ? "failed" : "part_failed");
            markEvent(event.getId(), status, null, null);
        } catch (Exception e) {
            log.error("手动发送失败 eventId={}: {}", event.getId(), e.getMessage(), e);
            markEvent(event.getId(), "failed", null, null);
        }
    }

    /**
     * 规则条件匹配：condition 为空匹配全部；否则按字段/标签相等匹配
     */
    private boolean matchCondition(NotificationRule rule, EventRecord event) {
        Map<String, Object> condition = ruleService.parseCondition(rule);
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            String expected = String.valueOf(entry.getValue());
            String actual = conditionValue(entry.getKey(), event);
            if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取条件字段的实际值（优先级：事件字段 > labels）
     */
    private String conditionValue(String field, EventRecord event) {
        switch (field) {
            case "severity":
                return event.getSeverity() == null ? "" : event.getSeverity();
            case "device_type":
                return event.getDeviceType() == null ? "" : event.getDeviceType();
            case "device_ip":
                return event.getDeviceIp() == null ? "" : event.getDeviceIp();
            case "event_source":
                return event.getEventSource() == null ? "" : event.getEventSource();
            default:
                // 从 labels_json 中取
                try {
                    Map<String, Object> labels = objectMapper.readValue(event.getLabelsJson(), new TypeReference<>() {
                    });
                    Object v = labels.get(field);
                    return v == null ? "" : String.valueOf(v);
                } catch (Exception e) {
                    return "";
                }
        }
    }

    /**
     * 组装模板变量（事件字段 + labels + time）
     */
    private Map<String, Object> buildVars(EventRecord event) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("device_name", nullToEmpty(event.getDeviceName()));
        vars.put("device_ip", nullToEmpty(event.getDeviceIp()));
        vars.put("device_type", nullToEmpty(event.getDeviceType()));
        vars.put("location", nullToEmpty(event.getLocation()));
        // V1.1.10 兼容：部分历史模板使用 {{device_location}}（依赖 AlertManager labels 才可渲染），
        // 此处显式注入，保证手动触发等无 labels 场景也能正确渲染
        vars.put("device_location", nullToEmpty(event.getLocation()));
        vars.put("severity", nullToEmpty(event.getSeverity()));
        vars.put("event_type", nullToEmpty(event.getEventType()));
        vars.put("biz_id", nullToEmpty(event.getBizId()));
        vars.put("tenant_name", resolveTenantName(event.getTenantId()));
        vars.put("time", event.getCreateTime() == null ? ""
                : event.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        // labels 合并进变量
        if (StringUtils.hasText(event.getLabelsJson())) {
            try {
                Map<String, Object> labels = objectMapper.readValue(event.getLabelsJson(), new TypeReference<>() {
                });
                labels.forEach(vars::putIfAbsent);
            } catch (Exception ignored) {
            }
        }
        return vars;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 按租户 ID 解析租户名称（sys_tenant 为系统表，不受租户拦截；查不到/异常兜底空串）
     */
    private String resolveTenantName(Long tenantId) {
        if (tenantId == null) {
            return "";
        }
        try {
            SysTenant tenant = tenantMapper.selectById(tenantId);
            return tenant == null ? "" : nullToEmpty(tenant.getTenantName());
        } catch (Exception e) {
            log.warn("解析租户名称失败 tenantId={}: {}", tenantId, e.getMessage());
            return "";
        }
    }

    /**
     * 回写事件状态/规则/渲染内容
     */
    private void markEvent(Long eventId, String status, Long ruleId, String ruleName) {
        EventRecord update = new EventRecord();
        update.setId(eventId);
        update.setStatus(status);
        update.setRuleId(ruleId);
        update.setRuleName(ruleName);
        eventMapper.updateById(update);
    }

    /**
     * 分页查询事件（租户隔离）
     * 默认过滤掉 user_operation 类型的操作日志（操作日志单独在"操作日志"页面查看）
     */
    public PageResult<EventRecord> pageEvent(long pageNum, long pageSize, String eventType, String severity, String status) {
        Page<EventRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EventRecord> wrapper = new LambdaQueryWrapper<EventRecord>()
                .eq(StringUtils.hasText(eventType), EventRecord::getEventType, eventType)
                .eq(StringUtils.hasText(severity), EventRecord::getSeverity, severity)
                .eq(StringUtils.hasText(status), EventRecord::getStatus, status);
        // 默认过滤掉操作日志（user_operation），除非显式指定 eventType
        if (!StringUtils.hasText(eventType)) {
            wrapper.ne(EventRecord::getEventType, "user_operation");
        }
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(EventRecord::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(EventRecord::getId);
        Page<EventRecord> result = eventMapper.selectPage(page, wrapper);
        // 回显日志数（批量，避免循环查库）
        if (!result.getRecords().isEmpty()) {
            List<Long> eventIds = result.getRecords().stream().map(EventRecord::getId).toList();
            List<NotificationLog> logs = logService.listByEventIds(eventIds);
            Map<Long, Long> countMap = new HashMap<>();
            logs.forEach(l -> countMap.merge(l.getEventId(), 1L, Long::sum));
            result.getRecords().forEach(e -> e.setLogCount(countMap.getOrDefault(e.getId(), 0L).intValue()));
        }
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 分页查询操作日志（租户隔离）
     * 专门查询 event_type = 'user_operation' 的记录
     */
    public PageResult<EventRecord> pageOperationLog(long pageNum, long pageSize, String keyword) {
        Page<EventRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<EventRecord> wrapper = new LambdaQueryWrapper<EventRecord>()
                .eq(EventRecord::getEventType, "user_operation");
        // 关键字搜索：模块名/操作内容/URI
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(EventRecord::getDeviceName, keyword)
                    .or().like(EventRecord::getContent, keyword)
                    .or().like(EventRecord::getLocation, keyword));
        }
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(EventRecord::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(EventRecord::getId);
        Page<EventRecord> result = eventMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }
}
