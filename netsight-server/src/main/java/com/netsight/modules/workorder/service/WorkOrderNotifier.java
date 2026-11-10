package com.netsight.modules.workorder.service;

import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import com.netsight.modules.alert.service.NotificationChannelService;
import com.netsight.modules.alert.service.NotificationContactService;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单派单通知服务。
 * 从 WorkOrderService 抽出，负责把"工单派单"动作翻译成通知域的 EventRecord + 模板 + 通道发送。
 * 与工单状态机解耦：本类不写库，只负责组装变量、构造联系人、调用通道中心 dispatch。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderNotifier {

    private final NotificationTemplateMapper templateMapper;
    private final NotificationChannelService channelService;
    private final SysTenantMapper tenantMapper;
    private final NotificationContactService contactService;

    /**
     * 派单自动通知：复用事件中心通道中心（EventRecord + 临时规则模板 + 通道中心 dispatch）。
     * 由 WorkOrderService.dispatch 的 afterCommit 回调调用——事务已提交，HTTP 发送失败不回滚工单。
     */
    public void notifyRepairer(WorkOrder order, Repairer repairer) {
        try {
            NotificationTemplate mainTemplate = templateMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotificationTemplate>()
                            .eq(NotificationTemplate::getTemplateCode, "tpl_order_dispatch")
                            // 【加固】按工单所属租户过滤模板，防止不同租户模板串用（模板为租户级复制数据）
                            .eq(NotificationTemplate::getTenantId, order.getTenantId())
                            .eq(NotificationTemplate::getEnabled, 1)
                            .last("LIMIT 1"));
            if (mainTemplate == null) {
                log.warn("派单通知模板 tpl_order_dispatch 不存在，跳过通知");
                return;
            }
            NotificationRule rule = new NotificationRule();
            rule.setTemplateId(mainTemplate.getId());

            EventRecord event = new EventRecord();
            event.setTenantId(order.getTenantId());
            event.setEventType("order_dispatch");
            event.setEventSource("workorder");
            event.setSeverity("warning");
            event.setBizId(order.getOrderNo());
            event.setDeviceName(order.getDeviceName());
            event.setDeviceIp(order.getDeviceIp());
            event.setDeviceType(order.getDeviceType());
            event.setLocation(order.getDeviceLocation());
            event.setContent(order.getDescription());
            event.setStatus("pending");
            event.setRetryCount(0);

            Map<String, Object> vars = new HashMap<>();
            vars.put("orderNo", order.getOrderNo());
            vars.put("order_no", order.getOrderNo());
            vars.put("deviceName", order.getDeviceName() == null ? "" : order.getDeviceName());
            vars.put("device_name", order.getDeviceName() == null ? "" : order.getDeviceName());
            vars.put("deviceIp", order.getDeviceIp() == null ? "" : order.getDeviceIp());
            vars.put("device_ip", order.getDeviceIp() == null ? "" : order.getDeviceIp());
            vars.put("deviceType", order.getDeviceType() == null ? "" : order.getDeviceType());
            vars.put("device_type", order.getDeviceType() == null ? "" : order.getDeviceType());
            vars.put("deviceLocation", order.getDeviceLocation() == null ? "" : order.getDeviceLocation());
            vars.put("device_location", order.getDeviceLocation() == null ? "" : order.getDeviceLocation());
            String faultDesc = StringUtils.hasText(order.getDescription()) ? order.getDescription()
                    : ("设备" + ("offline".equals(order.getFaultType()) ? "离线" : "链路异常"));
            vars.put("faultDesc", faultDesc);
            vars.put("fault_desc", faultDesc);
            vars.put("repairerName", repairer.getName());
            vars.put("repairer_name", repairer.getName());
            vars.put("tenantName", resolveTenantName(order.getTenantId()));
            vars.put("tenant_name", resolveTenantName(order.getTenantId()));
            vars.put("content", order.getDescription() == null ? "" : order.getDescription());

            // 构造联系人：优先复用租户下与维修员手机号一致的已登记启用联系人（带 openid/appId），
            // 查不到仍保留手机号走短信，不阻断工单、不自动创建联系人。
            List<NotificationContact> contacts = new ArrayList<>();
            if (StringUtils.hasText(repairer.getPhone())) {
                NotificationContact matched = contactService
                        .listByTenantAndPhone(order.getTenantId(), repairer.getPhone()).stream()
                        .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                        .findFirst().orElse(null);
                if (matched != null) {
                    contacts.add(matched);
                } else {
                    NotificationContact c = new NotificationContact();
                    c.setName(repairer.getName());
                    c.setMobile(repairer.getPhone());
                    contacts.add(c);
                }
            }
            if (contacts.isEmpty()) {
                log.warn("维修人员[{}]未配置手机号，派单通知跳过", repairer.getName());
                return;
            }
            // 模板即通道：派单通知由规则绑定的模板组决定通道，不再查租户全部通道
            List<NotificationLog> logs = channelService.dispatch(event, rule, contacts, null, vars, null);
            log.info("工单[{}]派单通知已发送，日志{}条", order.getOrderNo(), logs.size());
        } catch (Exception e) {
            log.error("工单[{}]派单通知异常: {}", order.getOrderNo(), e.getMessage(), e);
        }
    }

    /**
     * 按租户 ID 解析租户名称（sys_tenant 为系统表，不受租户拦截；查不到/异常兜底空串）。
     */
    private String resolveTenantName(Long tenantId) {
        if (tenantId == null) {
            return "";
        }
        try {
            SysTenant tenant = tenantMapper.selectById(tenantId);
            return tenant == null ? "" : (tenant.getTenantName() == null ? "" : tenant.getTenantName());
        } catch (Exception e) {
            log.warn("解析租户名称失败 tenantId={}: {}", tenantId, e.getMessage());
            return "";
        }
    }
}
