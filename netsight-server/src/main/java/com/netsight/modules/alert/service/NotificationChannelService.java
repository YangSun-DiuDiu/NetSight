package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.netsight.modules.alert.channel.ChannelRegistry;
import com.netsight.modules.alert.channel.NotificationChannelSender;
import com.netsight.modules.alert.channel.RecipientResult;
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
import java.util.HashMap;
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

    private static final Map<String, String> MANUAL_TEMPLATE_CODE = Map.ofEntries(
            Map.entry("manual_notify", "tpl_manual"),
            Map.entry("stock_low", "tpl_stock_low"),
            Map.entry("order_dispatch", "tpl_order_dispatch"),
            Map.entry("device_offline", "tpl_device_offline"),
            Map.entry("device_line_abnormal", "tpl_device_line"),
            Map.entry("device_recovered", "tpl_device_recovered"),
            Map.entry("device_cpu_high", "tpl_device_cpu_high"),
            Map.entry("device_mem_high", "tpl_device_mem_high")
    );

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
        // 模板即通道：规则绑定模板组（templateCode），发送时按模板查全部通道模板遍历发送，不再读规则通道列表
        if (rule != null && rule.getTemplateId() != null) {
            return dispatchByTemplateGroup(event, rule, contacts, vars, extra);
        }
        // 手动发送：rule==null，按前端传入通道实例 ID 发送
        return dispatchByChannelIds(event, contacts, channelIds, vars, extra);
    }

    /**
     * 模板即通道：按规则绑定的模板组（templateCode）查该租户下全部启用模板，
     * 每条模板自带 channelId（通道实例），遍历各通道渲染+发送。多通道=同 templateCode 多条模板。
     */
    private List<NotificationLog> dispatchByTemplateGroup(EventRecord event, NotificationRule rule,
                                                         List<NotificationContact> contacts,
                                                         Map<String, Object> vars, Map<String, Object> extra) {
        List<NotificationLog> logs = new ArrayList<>();
        NotificationTemplate mainTemplate = templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getId, rule.getTemplateId())
                .eq(NotificationTemplate::getTenantId, event.getTenantId())
                .last("LIMIT 1"));
        if (mainTemplate == null) {
            log.warn("规则[{}]模板[{}]不属于事件租户[{}]，跳过发送", rule.getId(), rule.getTemplateId(), event.getTenantId());
            return logs;
        }
        List<NotificationTemplate> groupTemplates = templateMapper.selectList(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, mainTemplate.getTemplateCode())
                .eq(NotificationTemplate::getTenantId, event.getTenantId())
                .eq(NotificationTemplate::getEnabled, 1));
        if (groupTemplates.isEmpty()) {
            log.warn("模板组[{}]无启用模板，跳过发送", mainTemplate.getTemplateCode());
            return logs;
        }
        List<String> doneChannelKeys = new ArrayList<>();
        for (NotificationTemplate tpl : groupTemplates) {
            // 模板绑定了具体通道实例则用之；未绑（channelId 为空）则按通道类型自动选该租户默认启用实例
            Map<String, Object> ch = resolveChannelInstance(tpl, event.getTenantId());
            if (ch == null) {
                log.warn("模板[{}]通道类型[{}]无可用实例，跳过", tpl.getId(), tpl.getChannelType());
                continue;
            }
            String channelType = String.valueOf(ch.get("channelType"));
            String channelKey = channelKey(channelType, ch);
            if (doneChannelKeys.contains(channelKey)) {
                continue;
            }
            List<String> channelReceivers = contactService.resolveReceiversByChannel(
                    contacts, channelType, appIdOf(ch));
            NotificationLog logEntry = sendOneResolved(event, rule, tpl, channelType, channelReceivers, vars, extra, ch);
            logs.add(logEntry);
            doneChannelKeys.add(channelKey);

            if (fallbackEnabled && logEntry.getSuccess() != null && logEntry.getSuccess() == 0
                    && ("sms".equals(channelType) || channelType.endsWith("_sms")
                    || channelType.endsWith("sms"))
                    && !doneChannelKeys.contains("wechat_work#null")) {
                log.warn("短信发送失败，自动降级企业微信重发 (eventId={})", event.getId());
                for (NotificationTemplate wxTpl : groupTemplates) {
                    Map<String, Object> wxCh = resolveChannelInstance(wxTpl, event.getTenantId());
                    if (wxCh == null) continue;
                    if (!"wechat_work".equals(String.valueOf(wxCh.get("channelType")))) continue;
                    List<String> wxReceivers = contactService.resolveReceiversByChannel(contacts, "wechat_work");
                    NotificationLog fallback = sendOneResolved(event, rule, wxTpl, "wechat_work", wxReceivers, vars, extra, wxCh);
                    logs.add(fallback);
                    doneChannelKeys.add(channelKey("wechat_work", wxCh));
                    break;
                }
            }
        }
        return logs;
    }

    /**
     * 解析模板绑定的通道实例（方案B：所有消息通道必须显式绑定 channelId，无类型兜底）。
     * channelId 为空直接返回 null，由调用方跳过发送并记录 warning；无可用实例返回 null。
     */
    private Map<String, Object> resolveChannelInstance(NotificationTemplate tpl, Long tenantId) {
        if (tpl.getChannelId() == null) {
            log.warn("模板[{}]未绑定通道实例（channelId 为空），跳过发送 (tenantId={})", tpl.getId(), tenantId);
            return null;
        }
        List<Map<String, Object>> resolved = notifyChannelService.resolveChannels(List.of(tpl.getChannelId()));
        return resolved.isEmpty() ? null : resolved.get(0);
    }

    /**
     * 模板组单通道发送：直接用传入模板渲染，不再 findTemplate
     */
    private NotificationLog sendOneResolved(EventRecord event, NotificationRule rule,
                                           NotificationTemplate template, String channelType,
                                           List<String> receivers, Map<String, Object> vars,
                                           Map<String, Object> extra, Map<String, Object> channelInstance) {
        NotificationChannelSender sender;
        try {
            sender = channelRegistry.get(channelType);
        } catch (Exception e) {
            return buildFailLog(event, rule, channelType, receivers, "通道未注册: " + channelType);
        }
        String rawContent = template == null ? null : template.getContent();
        // 微信公众号的 content 是模板描述符 JSON，必须原样交给 sender 解析；渲染由 sender 完成
        String sendContent = "wechat".equals(channelType) ? rawContent : renderContent(rawContent, vars);
        @SuppressWarnings("unchecked")
        Map<String, Object> chCfg = channelInstance == null ? null
                : (Map<String, Object>) channelInstance.get("config");
        Long channelId = channelIdOf(channelInstance);
        Map<String, Object> sendExtra = buildExtra(extra);
        SendRequest request = SendRequest.builder()
                .receiverList(receivers)
                .contentVars(vars)
                .content(sendContent)
                .channelId(channelId)
                .bizId(event.getBizId())
                .tenantId(event.getTenantId())
                .gatewayCode(parseGatewayCode(event.getLabelsJson()))
                .extra(sendExtra)
                .channelConfig(chCfg)
                .build();
        RetrySendResult result = sendWithRetry(sender, request);
        NotificationLog logEntry = new NotificationLog();
        logEntry.setTenantId(event.getTenantId());
        logEntry.setEventId(event.getId());
        logEntry.setRuleId(rule == null ? null : rule.getId());
        logEntry.setRuleName(rule == null ? null : rule.getRuleName());
        logEntry.setEventType(event.getEventType());
        logEntry.setChannelType(channelType);
        logEntry.setChannelId(channelId);
        logEntry.setReceiversJson(toJson(receivers));
        logEntry.setContent(logContent(sendContent, result.sendResult));
        logEntry.setBizId(event.getBizId());
        logEntry.setSuccess(result.sendResult.isSuccess() ? 1 : 0);
        logEntry.setErrorMsg(result.sendResult.getErrorMsg());
        logEntry.setThirdPartyMsgId(result.sendResult.getThirdPartyMsgId());
        logEntry.setTemplateId(result.sendResult.getTemplateId());
        if (result.sendResult.getRecipientResults() != null) {
            logEntry.setRecipientResultsJson(toJson(result.sendResult.getRecipientResults()));
        }
        logEntry.setCostTime(result.sendResult.getCostTime());
        logEntry.setRetryCount(result.sendResult.getRecipientResults() == null
                ? result.retryCount : maxRecipientRetry(result.sendResult.getRecipientResults()));
        logMapper.insert(logEntry);
        return logEntry;
    }

    /**
     * 手动发送：按传入通道实例 ID 遍历发送（无模板组概念，findTemplate 两级兜底）
     */
    private List<NotificationLog> dispatchByChannelIds(EventRecord event,
                                                      List<NotificationContact> contacts, List<Long> channelIds,
                                                      Map<String, Object> vars, Map<String, Object> extra) {
        List<NotificationLog> logs = new ArrayList<>();
        if (channelIds == null || channelIds.isEmpty()) {
            return logs;
        }
        NotificationTemplate mainTemplate = findManualTemplate(event.getTenantId(), event.getEventType());
        // 按渠道实例 ID 解析实例配置（channelType + 解密后的 config）
        List<Map<String, Object>> channels = notifyChannelService.resolveChannels(channelIds);
        List<String> doneChannelKeys = new ArrayList<>();
        for (Map<String, Object> ch : channels) {
            String channelType = String.valueOf(ch.get("channelType"));
            String channelKey = channelKey(channelType, ch);
            if (doneChannelKeys.contains(channelKey)) {
                continue;
            }
            // 按通道解析投递地址：sms→联系人mobile，wechat→openid，pushplus→空（租户token群发）
            List<String> channelReceivers = contactService.resolveReceiversByChannel(
                    contacts, channelType, appIdOf(ch));
            NotificationLog logEntry = sendOne(event, null, mainTemplate, channelType,
                    channelReceivers, vars, extra, ch);
            logs.add(logEntry);
            doneChannelKeys.add(channelKey);

            // 降级策略：短信发送失败自动降级为公众号，确保至少一个通道触达
            if (!logEntry.getSuccess().equals(1) && fallbackEnabled
                    && ("sms".equals(channelType) || channelType.endsWith("_sms")
                    || channelType.endsWith("sms"))
                    && !doneChannelKeys.contains("wechat_work#null")) {
                log.warn("短信发送失败，自动降级企业微信通道重发 (eventId={})", event.getId());
                // 找租户启用的企业微信渠道实例重发
                List<Map<String, Object>> wxChannels = notifyChannelService.resolveChannels(channelIds).stream()
                        .filter(c -> "wechat_work".equals(c.get("channelType"))).toList();
                if (!wxChannels.isEmpty()) {
                    Map<String, Object> wxCh = wxChannels.get(0);
                    List<String> wechatReceivers = contactService.resolveReceiversByChannel(contacts, "wechat_work");
                    NotificationLog fallback = sendOne(event, null, mainTemplate, "wechat_work",
                            wechatReceivers, vars, extra, wxCh);
                    logs.add(fallback);
                    doneChannelKeys.add(channelKey("wechat_work", wxCh));
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

        // 1. 选择该通道实例的模板（方案B：仅按具体通道实例 channelId 找专属模板，无类型兜底）
        Long channelInstanceId = channelInstance == null ? null
                : (channelInstance.get("channelId") == null ? null : ((Number) channelInstance.get("channelId")).longValue());
        NotificationTemplate template = findTemplate(mainTemplate, channelType, channelInstanceId, event.getTenantId());
        // 2. 渲染内容（无模板时手动发送场景直接取 vars.content）
        String rawContent = template == null
                ? (mainTemplate == null ? null : mainTemplate.getContent())
                : template.getContent();
        // 微信公众号的 content 是模板描述符 JSON，必须原样交给 sender 解析
        String sendContent = "wechat".equals(channelType)
                ? rawContent : renderContent(rawContent, vars);

        // 3. 发送（失败自动重试，间隔递增）
        @SuppressWarnings("unchecked")
        Map<String, Object> chCfg = channelInstance == null ? null
                : (Map<String, Object>) channelInstance.get("config");
        Long channelId = channelIdOf(channelInstance);
        Map<String, Object> sendExtra = buildExtra(extra);
        SendRequest request = SendRequest.builder()
                .receiverList(receivers)
                .contentVars(vars)
                .content(sendContent)
                .channelId(channelId)
                .bizId(event.getBizId())
                .tenantId(event.getTenantId())
                .gatewayCode(parseGatewayCode(event.getLabelsJson()))
                .extra(sendExtra)
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
        logEntry.setChannelId(channelId);
        logEntry.setReceiversJson(toJson(receivers));
        logEntry.setContent(logContent(sendContent, result.sendResult));
        logEntry.setBizId(event.getBizId());
        logEntry.setSuccess(result.sendResult.isSuccess() ? 1 : 0);
        logEntry.setErrorMsg(result.sendResult.getErrorMsg());
        logEntry.setThirdPartyMsgId(result.sendResult.getThirdPartyMsgId());
        logEntry.setTemplateId(result.sendResult.getTemplateId());
        if (result.sendResult.getRecipientResults() != null) {
            logEntry.setRecipientResultsJson(toJson(result.sendResult.getRecipientResults()));
        }
        logEntry.setCostTime(result.sendResult.getCostTime());
        logEntry.setRetryCount(result.sendResult.getRecipientResults() == null
                ? result.retryCount : maxRecipientRetry(result.sendResult.getRecipientResults()));
        logMapper.insert(logEntry);
        return logEntry;
    }

    /**
     * 手动发送按事件类型解析模板 code：manual_notify/stock_low/... 映射到 tpl_xxx，
     * 其他事件直接用 eventType 作为 code；查不到返回 null 回落 vars.content。
     */
    private NotificationTemplate findManualTemplate(Long tenantId, String eventType) {
        if (tenantId == null || eventType == null || eventType.isBlank()) {
            return null;
        }
        String code = MANUAL_TEMPLATE_CODE.getOrDefault(eventType, eventType);
        return templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, code)
                .eq(NotificationTemplate::getTenantId, tenantId)
                .eq(NotificationTemplate::getEnabled, 1)
                .last("LIMIT 1"));
    }

    private String channelKey(String channelType, Map<String, Object> ch) {
        return channelType + "#" + channelIdOf(ch);
    }

    private String appIdOf(Map<String, Object> ch) {
        if (ch == null || ch.get("config") == null) {
            return null;
        }
        Object cfg = ch.get("config");
        if (cfg instanceof Map<?, ?> map) {
            Object appId = map.get("appId");
            return appId == null ? null : String.valueOf(appId);
        }
        return null;
    }

    private Long channelIdOf(Map<String, Object> ch) {
        if (ch == null || ch.get("channelId") == null) {
            return null;
        }
        return ((Number) ch.get("channelId")).longValue();
    }

    private String renderContent(String rawContent, Map<String, Object> vars) {
        if (rawContent != null) {
            return NotificationTemplateService.render(rawContent, vars);
        }
        return vars != null && vars.get("content") != null
                ? String.valueOf(vars.get("content")) : "";
    }

    private String logContent(String sendContent, SendResult result) {
        if (result != null && result.getRenderedContent() != null
                && !result.getRenderedContent().isBlank()) {
            return result.getRenderedContent();
        }
        return sendContent == null ? "" : sendContent;
    }

    private int maxRecipientRetry(List<RecipientResult> results) {
        int max = 0;
        for (RecipientResult r : results) {
            if (r.getRetryCount() > max) {
                max = r.getRetryCount();
            }
        }
        return max;
    }

    private Map<String, Object> buildExtra(Map<String, Object> extra) {
        return extra == null ? new HashMap<>() : new HashMap<>(extra);
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
        // 逐人发送的通道（recipientResults != null）已在 sender 内部按人重试，通道层不得整批重发
        while (!result.isSuccess() && result.isRetryable() && result.getRecipientResults() == null
                && retry < intervals.length) {
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
     * 找模板（方案B：仅按具体通道实例 channelId 找专属模板，无类型兜底）
     * 手动发送场景 mainTemplate 为 null 时直接返回 null（内容取 vars.content）。
     * （异步线程无登录态、租户过滤不生效，必须显式按事件租户过滤，防止跨租户串用模板）
     */
    private NotificationTemplate findTemplate(NotificationTemplate mainTemplate, String channelType, Long channelId, Long tenantId) {
        if (mainTemplate == null || channelId == null) {
            return null;
        }
        return templateMapper.selectOne(new LambdaQueryWrapper<NotificationTemplate>()
                .eq(NotificationTemplate::getTemplateCode, mainTemplate.getTemplateCode())
                .eq(NotificationTemplate::getChannelId, channelId)
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
