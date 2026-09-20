package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知通道中心（发送执行层）
 * 职责：模板选择与内容渲染 → 按通道列表调用适配器发送 → 失败重试 → 降级 → 发送日志落库
 * 不关心事件来源，只负责按给定内容/接收人通过指定通道发送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private final ChannelRegistry channelRegistry;
    private final NotificationTemplateMapper templateMapper;
    private final NotificationLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final NotificationContactService contactService;
    private final NotifyChannelService notifyChannelService;

    /** 失败重试间隔（ms），逗号分隔，如 1000,2000,3000 */
    @Value("${netsight.alert.retry-intervals:1000,2000,3000}")
    private String retryIntervals;

    /** 通道发送是否启用降级（sms 失败自动降级 wechat） */
    @Value("${netsight.alert.fallback-enabled:true}")
    private boolean fallbackEnabled;

    /**
     * 按规则分发事件通知：对 channels 中每个通道执行渲染+发送+日志
     *
     * @param event    事件（含变量）
     * @param rule     路由规则（含 templateId、channels），手动发送可为 null
     * @param contacts 通知联系人（不同通道按各自字段解析投递地址：sms→mobile，wechat→openid，pushplus→租户token忽略）
     * @param channelIds  渠道实例 ID 列表（notify_channel.id）
     * @param vars      模板变量
     * @param extra     扩展字段（跳转 URL 等）
     * @return 发送日志列表（全部通道）
     */
    public List<NotificationLog> dispatch(EventRecord event, NotificationRule rule,
                                          List<NotificationContact> contacts, List<Long> channelIds,
                                          Map<String, Object> vars, Map<String, Object> extra) {
        List<NotificationLog> logs = new ArrayList<>();
        if (channelIds == null || channelIds.isEmpty()) {
            return logs;
        }
        // 主模板（用于取 templateCode 与内容兜底；手动发送 rule 可为 null）
        // 【加固】按 模板ID + 事件租户 查询：dispatch 在异步线程执行，TenantLine 拦截器无租户上下文会跳过过滤，
        // 若仅 selectById，规则可引用其他租户的模板造成跨租户模板串用（内容泄露/误发），必须显式按事件租户匹配。
        NotificationTemplate mainTemplate = null;
        if (rule != null && rule.getTemplateId() != null) {
            mainTemplate = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                    .eq(NotificationTemplate::getId, rule.getTemplateId())
                    .eq(NotificationTemplate::getTenantId, event.getTenantId())
                    .last("LIMIT 1"));
            if (mainTemplate == null) {
                log.warn("规则[{}]模板[{}]不属于事件租户[{}]，按无模板兜底处理",
                        rule.getId(), rule.getTemplateId(), event.getTenantId());
            }
        }
        // 按渠道实例 ID 解析实例配置（channelType + 解密后的 config）
        List<Map<String, Object>> channels = notifyChannelService.resolveChannels(channelIds);
        List<String> doneChannelTypes = new ArrayList<>();
        for (Map<String, Object> ch : channels) {
            String channelType = String.valueOf(ch.get("channelType"));
            if (doneChannelTypes.contains(channelType)) {
                continue;
            }
            // 按通道解析投递地址：sms→联系人mobile，wechat→openid，pushplus→空（租户token群发）
            List<String> channelReceivers = contactService.resolveReceiversByChannel(contacts, channelType);
            NotificationLog logEntry = sendOne(event, rule, mainTemplate, channelType,
                    channelReceivers, vars, extra, ch);
            logs.add(logEntry);
            doneChannelTypes.add(channelType);

            // 降级策略：短信发送失败自动降级为公众号，确保至少一个通道触达
            if (!logEntry.getSuccess().equals(1) && fallbackEnabled
                    && ("sms".equals(channelType) || channelType.endsWith("_sms"))
                    && !doneChannelTypes.contains("wechat_work")) {
                log.warn("短信发送失败，自动降级企业微信通道重发 (eventId={})", event.getId());
                // 找租户启用的企业微信渠道实例重发
                List<Map<String, Object>> wxChannels = notifyChannelService.resolveChannels(channelIds).stream()
                        .filter(c -> "wechat_work".equals(c.get("channelType"))).toList();
                if (!wxChannels.isEmpty()) {
                    Map<String, Object> wxCh = wxChannels.get(0);
                    List<String> wechatReceivers = contactService.resolveReceiversByChannel(contacts, "wechat_work");
                    NotificationLog fallback = sendOne(event, rule, mainTemplate, "wechat_work",
                            wechatReceivers, vars, extra, wxCh);
                    logs.add(fallback);
                    doneChannelTypes.add("wechat_work");
                }
            }
        }
        return logs;
    }

    /**
     * 单通道发送：选模板 → 渲染 → 发送（失败重试）→ 日志落库
     */
    private NotificationLog sendOne(EventRecord event, NotificationRule rule,
                                    NotificationTemplate mainTemplate, String channelType,
                                    List<String> receivers, Map<String, Object> vars,
                                    Map<String, Object> extra, Map<String, Object> channelInstance) {
        NotificationChannelSender sender;
        try {
            sender = channelRegistry.get(channelType);
        } catch (Exception e) {
            return buildFailLog(event, rule, channelType, receivers, "通道未注册: " + channelType);
        }

        // 1. 选择该通道实例的模板（方案B：先按具体通道实例 channelId 找专属模板，找不到再按 channelType 找该类型默认模板兜底）
        Long channelInstanceId = channelInstance == null ? null
                : (channelInstance.get("channelId") == null ? null : ((Number) channelInstance.get("channelId")).longValue());
        NotificationTemplate template = findTemplate(mainTemplate, channelType, channelInstanceId, event.getTenantId());
        // 2. 渲染内容（无模板时手动发送场景直接取 vars.content）
        String rawContent = template == null
                ? (mainTemplate == null ? null : mainTemplate.getContent())
                : template.getContent();
        String content;
        if (rawContent != null) {
            content = NotificationTemplateService.render(rawContent, vars);
        } else if (vars != null && vars.get("content") != null) {
            content = String.valueOf(vars.get("content"));
        } else {
            content = "";
        }

        // 3. 发送（失败自动重试，间隔递增）
        @SuppressWarnings("unchecked")
        Map<String, Object> chCfg = channelInstance == null ? null
                : (Map<String, Object>) channelInstance.get("config");
        SendRequest request = SendRequest.builder()
                .receiverList(receivers)
                .templateId(template == null ? null : template.getId())
                .contentVars(vars)
                .content(content)
                .bizId(event.getBizId())
                .tenantId(event.getTenantId())
                .gatewayCode(parseGatewayCode(event.getLabelsJson()))
                .extra(extra)
                .channelConfig(chCfg)
                .build();

        RetrySendResult result = sendWithRetry(sender, request);

        // 4. 日志落库
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTenantId(event.getTenantId());
        logEntry.setEventId(event.getId());
        logEntry.setRuleId(rule == null ? null : rule.getId());
        logEntry.setRuleName(rule == null ? null : rule.getRuleName());
        logEntry.setEventType(event.getEventType());
        logEntry.setChannelType(channelType);
        logEntry.setReceiversJson(toJson(receivers));
        logEntry.setContent(content);
        logEntry.setBizId(event.getBizId());
        logEntry.setSuccess(result.sendResult.isSuccess() ? 1 : 0);
        logEntry.setErrorMsg(result.sendResult.getErrorMsg());
        logEntry.setThirdPartyMsgId(result.sendResult.getThirdPartyMsgId());
        logEntry.setCostTime(result.sendResult.getCostTime());
        logEntry.setRetryCount(result.retryCount);
        logMapper.insert(logEntry);
        return logEntry;
    }

    /**
     * 从事件 labels JSON 解析来源网关编码。
     * AlertManager 链路（采集标签）天然携带 gateway_code；标准格式 webhook 未传时返回 null（回退租户级）。
     */
    private String parseGatewayCode(String labelsJson) {
        if (labelsJson == null || labelsJson.isEmpty()) {
            return null;
        }
        try {
            Map<?, ?> labels = objectMapper.readValue(labelsJson, Map.class);
            Object code = labels.get("gateway_code");
            return code == null ? null : String.valueOf(code);
        } catch (Exception e) {
            log.warn("解析事件 labels 获取 gateway_code 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 发送 + 失败重试（最多 3 次，间隔 10s/30s/60s 生产建议值，开发可配短间隔）
     */
    private RetrySendResult sendWithRetry(NotificationChannelSender sender, SendRequest request) {
        int[] intervals = parseIntervals();
        SendResult result = sender.send(request);
        int retry = 0;
        while (!result.isSuccess() && retry < intervals.length) {
            retry++;
            log.warn("通道[{}]发送失败，第{}次重试: {}", sender.getChannelType(), retry, result.getErrorMsg());
            try {
                Thread.sleep(intervals[retry - 1]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            result = sender.send(request);
        }
        if (!result.isSuccess()) {
            log.error("通道[{}]发送最终失败: {}", sender.getChannelType(), result.getErrorMsg());
        }
        return new RetrySendResult(result, retry);
    }

    /** 发送结果 + 重试次数 */
    private record RetrySendResult(SendResult sendResult, int retryCount) {
    }

    /** 解析重试间隔配置 */
    private int[] parseIntervals() {
        try {
            String[] parts = retryIntervals.split(",");
            int[] intervals = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                intervals[i] = Integer.parseInt(parts[i].trim());
            }
            return intervals;
        } catch (Exception e) {
            return new int[]{1000, 2000, 3000};
        }
    }

    /**
     * 找模板（方案B：两级查找）
     * 1) 优先按 具体通道实例 channelId 找专属模板（模板直接绑定已配好参数的通道实例）；
     * 2) 找不到（channelId 为空或无专属模板）再按 channelType 找该类型默认模板兜底。
     * （异步线程无登录态、租户过滤不生效，必须显式按事件租户过滤，防止跨租户串用模板）
     */
    private NotificationTemplate findTemplate(NotificationTemplate mainTemplate, String channelType, Long channelId, Long tenantId) {
        if (mainTemplate == null) {
            return null;
        }
        // 第一级：按具体通道实例找专属模板
        if (channelId != null) {
            NotificationTemplate instanceTpl = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                    .eq(NotificationTemplate::getTemplateCode, mainTemplate.getTemplateCode())
                    .eq(NotificationTemplate::getChannelId, channelId)
                    .eq(NotificationTemplate::getTenantId, tenantId)
                    .eq(NotificationTemplate::getEnabled, 1)
                    .last("LIMIT 1"));
            if (instanceTpl != null) {
                return instanceTpl;
            }
        }
        // 第二级：按 channelType 找该类型默认模板兜底
        return templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, mainTemplate.getTemplateCode())
                .eq(NotificationTemplate::getChannelType, channelType)
                .eq(NotificationTemplate::getTenantId, tenantId)
                .eq(NotificationTemplate::getEnabled, 1)
                .last("LIMIT 1"));
    }

    private NotificationLog buildFailLog(EventRecord event, NotificationRule rule, String channelType,
                                         List<String> receivers, String error) {
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTenantId(event.getTenantId());
        logEntry.setEventId(event.getId());
        logEntry.setRuleId(rule == null ? null : rule.getId());
        logEntry.setRuleName(rule == null ? null : rule.getRuleName());
        logEntry.setEventType(event.getEventType());
        logEntry.setChannelType(channelType);
        logEntry.setReceiversJson(toJson(receivers));
        logEntry.setContent(event.getContent());
        logEntry.setBizId(event.getBizId());
        logEntry.setSuccess(0);
        logEntry.setErrorMsg(error);
        logMapper.insert(logEntry);
        return logEntry;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
