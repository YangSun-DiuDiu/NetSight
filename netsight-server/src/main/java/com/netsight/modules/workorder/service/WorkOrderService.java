package com.netsight.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.config.PushWebSocketHandler;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.mapper.NotificationContactMapper;
import com.netsight.modules.alert.mapper.NotificationRuleMapper;
import com.netsight.modules.workorder.constant.WorkOrderStatus;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.entity.WorkOrderPart;
import com.netsight.modules.workorder.entity.WorkOrderRecord;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.mapper.ApprovalBillMapper;
import com.netsight.modules.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 故障工单服务（联系单 + 报修派单闭环）
 * 职责：工单查询/CRUD → 事件联动自动建单（24h 去重）→ 派单状态机 → 完工关闭。
 * 派单通知发送已抽到 {@link WorkOrderNotifier}，事件路由已抽到
 * {@link com.netsight.modules.workorder.listener.WorkOrderEventListener}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WorkOrderMapper orderMapper;
    private final WorkOrderRecordMapper recordMapper;
    private final WorkOrderPartMapper orderPartMapper;
    private final RepairerMapper repairerMapper;
    private final NotificationRuleMapper ruleMapper;
    private final NotificationContactMapper contactMapper;
    private final WorkOrderNotifier notifier;
    private final WorkflowService workflowService;
    private final ApprovalBillMapper approvalBillMapper;

    /** 工单完工验收流程（租户未发布时走原有直接完工逻辑，保持向后兼容） */
    public static final String FLOW_WORKORDER_ACCEPT = "work_order_accept";
    public static final String BIZ_WORKORDER_ACCEPT = "work_order_accept";

    /** 故障类型映射（事件类型 → 工单故障类型/描述） */
    private static final Map<String, String> FAULT_TYPE = Map.of(
            "device_offline", "offline",
            "device_line_abnormal", "line_abnormal");

    // ==================== 查询 ====================

    /**
     * 工单分页查询（租户隔离由多租户插件自动处理）
     */
    public PageResult<WorkOrder> pageOrder(long pageNum, long pageSize, String orderNo,
                                           Integer status, String deviceName, String faultType) {
        Page<WorkOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .like(StringUtils.hasText(orderNo), WorkOrder::getOrderNo, orderNo)
                .eq(status != null, WorkOrder::getStatus, status)
                .like(StringUtils.hasText(deviceName), WorkOrder::getDeviceName, deviceName)
                .eq(StringUtils.hasText(faultType), WorkOrder::getFaultType, faultType)
                .orderByDesc(WorkOrder::getId);
        Page<WorkOrder> result = orderMapper.selectPage(page, wrapper);
        List<WorkOrder> records = result.getRecords();
        if (!records.isEmpty()) {
            // 【优化】批量 IN 统计记录数与备件数（2 次查询），替代循环内逐行 selectCount（原 N*2 次查询）
            List<Long> orderIds = records.stream().map(WorkOrder::getId).collect(Collectors.toList());
            Map<Long, Long> recordCountMap = recordMapper.selectList(
                            new LambdaQueryWrapper<WorkOrderRecord>().in(WorkOrderRecord::getOrderId, orderIds))
                    .stream().collect(Collectors.groupingBy(WorkOrderRecord::getOrderId, Collectors.counting()));
            Map<Long, Long> partCountMap = orderPartMapper.selectList(
                            new LambdaQueryWrapper<WorkOrderPart>().in(WorkOrderPart::getOrderId, orderIds))
                    .stream().collect(Collectors.groupingBy(WorkOrderPart::getOrderId, Collectors.counting()));
            // 待验收标记：批量查询验收审批中的审批单（bizId=工单编号），状态文案补充“待验收”
            List<String> orderNos = records.stream().map(WorkOrder::getOrderNo).collect(Collectors.toList());
            Map<String, Long> pendingAcceptMap = approvalBillMapper.selectList(new LambdaQueryWrapper<ApprovalBill>()
                            .eq(ApprovalBill::getBizType, BIZ_WORKORDER_ACCEPT)
                            .eq(ApprovalBill::getApprovalStatus, WorkflowService.STATUS_PROCESSING)
                            .in(ApprovalBill::getBizId, orderNos))
                    .stream().collect(Collectors.toMap(ApprovalBill::getBizId, ApprovalBill::getId, (a, b) -> b));
            records.forEach(o -> {
                String text = WorkOrderStatus.textOf(o.getStatus());
                if (o.getStatus() != null && o.getStatus() == 2 && pendingAcceptMap.containsKey(o.getOrderNo())) {
                    text = text + "（待验收）";
                }
                o.setStatusText(text);
                o.setRecordCount(Math.toIntExact(recordCountMap.getOrDefault(o.getId(), 0L)));
                o.setPartCount(Math.toIntExact(partCountMap.getOrDefault(o.getId(), 0L)));
            });
        }
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 工单详情：基础信息 + 处理记录 + 备件关联
     */
    public Map<String, Object> detail(Long id) {
        WorkOrder order = checkTenantOrder(id);
        Map<String, Object> result = new HashMap<>();
        order.setStatusText(WorkOrderStatus.textOf(order.getStatus()));
        result.put("order", order);
        result.put("records", recordMapper.selectList(new LambdaQueryWrapper<WorkOrderRecord>()
                .eq(WorkOrderRecord::getOrderId, id).orderByDesc(WorkOrderRecord::getId)));
        result.put("parts", orderPartMapper.selectList(new LambdaQueryWrapper<WorkOrderPart>()
                .eq(WorkOrderPart::getOrderId, id).orderByDesc(WorkOrderPart::getId)));
        // 完工验收审批信息（未配置该流程的租户返回空列表）
        result.put("acceptBills", approvalBillMapper.selectList(new LambdaQueryWrapper<ApprovalBill>()
                .eq(ApprovalBill::getBizType, BIZ_WORKORDER_ACCEPT)
                .eq(ApprovalBill::getBizId, order.getOrderNo())
                .orderByDesc(ApprovalBill::getId)));
        return result;
    }

    /**
     * 工单统计（顶部卡片：待处理/已派单/维修中/已完成/已自动恢复）
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 0)));
        stats.put("dispatched", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 1)));
        stats.put("repairing", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 2)));
        stats.put("completed", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 3)));
        stats.put("recovered", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 5)));
        stats.put("total", orderMapper.selectCount(new LambdaQueryWrapper<>()));
        return stats;
    }

    // ==================== 维护 ====================

    /**
     * 手动新增工单（租户取当前登录上下文）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long addOrder(WorkOrder order) {
        return addOrder(order, SecurityUtils.getTenantId());
    }

    /**
     * 新增工单（显式指定租户）。
     * 供系统内部调用（如点检异常自动报修）传入业务归属租户——调用方必须保证 tenantId 与业务上下文一致，
     * 否则会跨租户落库；对外入口统一走无参重载（取当前登录租户）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long addOrder(WorkOrder order, Long tenantId) {
        order.setTenantId(tenantId);
        order.setOrderNo(genNo("WO"));
        if (order.getSourceType() == null) {
            order.setSourceType("manual");
        }
        if (order.getStatus() == null) {
            order.setStatus(0);
        }
        if (!StringUtils.hasText(order.getFaultType())) {
            order.setFaultType("manual");
        }
        if (!StringUtils.hasText(order.getSeverity())) {
            order.setSeverity("info");
        }
        orderMapper.insert(order);
        addRecord(tenantId, order.getId(), "auto_create", currentUsername(), "联系单生成（手动）：" + (StringUtils.hasText(order.getDescription()) ? order.getDescription() : order.getDeviceName()));
        return order.getId();
    }

    /**
     * 修改工单（仅未派单状态允许）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOrder(WorkOrder order) {
        WorkOrder exist = checkTenantOrder(order.getId());
        if (exist.getStatus() != null && exist.getStatus() != 0) {
            throw new ServiceException(500, "仅待处理状态可修改工单");
        }
        order.setOrderNo(null);
        order.setTenantId(null);
        order.setSourceType(null);
        order.setStatus(null);
        order.setEventId(null);
        order.setRepairerId(null);
        order.setRepairerName(null);
        order.setDispatchTime(null);
        order.setRepairStartTime(null);
        order.setRepairEndTime(null);
        orderMapper.updateById(order);
    }

    /**
     * 删除工单（软删，级联删除处理记录与备件关联）
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeOrder(Long id) {
        checkTenantOrder(id);
        orderMapper.deleteById(id);
        recordMapper.delete(new LambdaQueryWrapper<WorkOrderRecord>().eq(WorkOrderRecord::getOrderId, id));
        orderPartMapper.delete(new LambdaQueryWrapper<WorkOrderPart>().eq(WorkOrderPart::getOrderId, id));
    }

    // ==================== 事件联动（由 WorkOrderEventListener 路由进入） ====================

    /**
     * 告警事件自动生成联系单（方案9.1）
     * 规则：device_offline / device_line_abnormal 自动建单；同一设备同一故障 24h 内不重复
     */
    @Transactional(rollbackFor = Exception.class)
    public Long autoCreateFromEvent(EventRecord event) {
        String faultType = FAULT_TYPE.get(event.getEventType());
        if (faultType == null) {
            return null;
        }
        // 24h 去重：同一设备同一故障类型 24h 内已有未关闭工单则不再生成
        // 注意：work_order.device_code 列当前承载"设备标识"，建单时写入的是设备 IP（见下方 setDeviceCode(event.getDeviceIp())），
        // 因此这里按 device_code=event.deviceIp 匹配；如需改为独立 device_ip 列，需同步迁移存量数据（勿单独改一边）。
        // 【加固】① 显式附加 tenant_id = event.tenantId：本方法在 @EventListener 异步线程执行，无登录租户上下文，
        //    TenantLine 拦截器会跳过租户过滤，若不显式加租户条件，去重查询会命中其他租户的同设备工单导致漏建单；
        // ② deviceIp 与 deviceName 均为空时跳过去重（eq(null) 会被 MyBatis-Plus 忽略变成全表扫描，无意义），
        //    直接进入建单流程。
        String deviceKey = event.getDeviceIp() == null ? event.getDeviceName() : event.getDeviceIp();
        boolean dedupCheckable = StringUtils.hasText(deviceKey);
        if (dedupCheckable) {
            LocalDateTime since = LocalDateTime.now().minusHours(24);
            Long exist = orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getTenantId, event.getTenantId())
                    .eq(WorkOrder::getDeviceCode, deviceKey)
                    .eq(WorkOrder::getFaultType, faultType)
                    .in(WorkOrder::getStatus, 0, 1, 2)
                    .ge(WorkOrder::getCreateTime, since));
            if (exist != null && exist > 0) {
                log.info("事件[{}]命中 24h 去重，同设备同故障已有工单，跳过自动建单", event.getId());
                return null;
            }
        }
        WorkOrder order = new WorkOrder();
        order.setTenantId(event.getTenantId());
        order.setOrderNo(genNo("WO"));
        order.setSourceType("event");
        order.setEventId(event.getId());
        order.setDeviceCode(event.getDeviceIp());
        order.setDeviceName(event.getDeviceName());
        order.setDeviceIp(event.getDeviceIp());
        order.setDeviceType(event.getDeviceType());
        order.setDeviceLocation(event.getLocation());
        order.setFaultType(faultType);
        order.setSeverity(event.getSeverity() == null ? "warning" : event.getSeverity());
        order.setDescription(event.getContent());
        // 【微信告警直达接单】解析该租户 device_offline/line_abnormal 规则的接收人，
        // 若接收人手机号能与本租户维修人员档案精确匹配，则建单即指派（status=1 待接单），
        // 使维修人员从微信公众号「查看详情」进入 H5 待接单列表时能直接看到并接单；
        // 未命中则保持 status=0，仍走管理员派单流程（不改变原有审核路径）。
        Repairer assignee = resolveAlertAssignee(event);
        if (assignee != null) {
            order.setRepairerId(assignee.getId());
            order.setStatus(1);
        } else {
            order.setStatus(0);
        }
        orderMapper.insert(order);
        String recordContent = assignee != null
                ? "告警事件[" + event.getEventType() + "]自动生成工单，已指派给告警接收人[" + assignee.getName() + "]"
                : "告警事件[" + event.getEventType() + "]自动生成联系单，等待管理员审核";
        addRecord(event.getTenantId(), order.getId(), "auto_create", "系统", recordContent);
        log.info("告警事件[{}]自动生成工单[{}]{}", event.getId(), order.getOrderNo(),
                assignee != null ? "，已指派给维修人员[" + assignee.getName() + "]" : "");
        pushOrder(order, "auto_create");
        return order.getId();
    }

    /**
     * 解析告警接收人对应的本租户维修人员档案。
     *
     * <p>链路：按 event_type 取该租户已启用规则 → 解析 receiver_strategy_json 的 contactIds
     * → 取联系人 mobile → 按同租户 repairer.phone 精确匹配（启用、未删除）。
     * 命中第一个即返回；任一步失败只记 warn 并回退为"待派单"，不影响建单。
     *
     * <p>注意：本方法运行在事件异步线程，无登录租户上下文，所有查询必须显式带 tenantId。
     */
    private Repairer resolveAlertAssignee(EventRecord event) {
        if (event.getTenantId() == null) {
            return null;
        }
        try {
            List<NotificationRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<NotificationRule>()
                    .eq(NotificationRule::getTenantId, event.getTenantId())
                    .eq(NotificationRule::getEventType, event.getEventType())
                    .eq(NotificationRule::getEnabled, 1)
                    .orderByAsc(NotificationRule::getId));
            for (NotificationRule rule : rules) {
                for (Long contactId : parseContactIds(rule.getReceiverStrategyJson())) {
                    NotificationContact contact = contactMapper.selectOne(new LambdaQueryWrapper<NotificationContact>()
                            .eq(NotificationContact::getTenantId, event.getTenantId())
                            .eq(NotificationContact::getId, contactId)
                            .eq(NotificationContact::getStatus, 1)
                            .last("LIMIT 1"));
                    if (contact == null || !StringUtils.hasText(contact.getMobile())) {
                        continue;
                    }
                    Repairer repairer = repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                            .eq(Repairer::getTenantId, event.getTenantId())
                            .eq(Repairer::getPhone, contact.getMobile())
                            .eq(Repairer::getStatus, 1)
                            .last("LIMIT 1"));
                    if (repairer != null) {
                        return repairer;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("事件[{}]解析告警接收人失败，工单保持待派单: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /**
     * 从接收人策略 JSON 中提取 contactIds。
     * 用正则而非 JSON 解析，是为兼容历史脏数据（如 {type: fixed, contactIds: [2]} 缺引号）——
     * 这类脏数据不匹配带引号的键名，直接按"无接收人"处理，避免反序列化异常打断建单。
     */
    private List<Long> parseContactIds(String json) {
        List<Long> ids = new java.util.ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return ids;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"contactIds\"\\s*:\\s*\\[([^\\]]*)]")
                .matcher(json);
        if (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                String trimmed = part.trim();
                if (trimmed.matches("\\d+")) {
                    ids.add(Long.valueOf(trimmed));
                }
            }
        }
        return ids;
    }

    /**
     * 设备恢复事件：同一设备未完成工单自动标记「已自动恢复」（方案9.1）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleRecover(EventRecord event) {
        // 【加固】① 显式附加 tenant_id = event.tenantId：本方法在 @EventListener 异步线程执行，无登录租户上下文，
        //    TenantLine 拦截器会跳过租户过滤，若不显式加租户条件，会把其他租户的同设备未完成工单全部置为"已自动恢复"（跨租户篡改）；
        // ② deviceIp 为空时直接返回：eq(null) 会被 MyBatis-Plus 忽略变成"匹配全部未完成工单"，
        //    此前会导致一次恢复事件把所有租户所有未完成工单全部误归档。
        if (!StringUtils.hasText(event.getDeviceIp())) {
            log.warn("恢复事件[{}]缺少设备 IP，跳过工单自动归档", event.getId());
            return;
        }
        List<WorkOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getTenantId, event.getTenantId())
                .eq(WorkOrder::getDeviceIp, event.getDeviceIp())
                .in(WorkOrder::getStatus, 0, 1, 2)
                .orderByAsc(WorkOrder::getId));
        for (WorkOrder order : orders) {
            order.setStatus(5);
            orderMapper.updateById(order);
            addRecord(event.getTenantId(), order.getId(), "recover", "系统", "设备恢复，工单自动标记为已自动恢复（不再派单）");
            log.info("工单[{}]设备恢复自动归档", order.getOrderNo());
            pushOrder(order, "recover");
        }
    }

    // ==================== 报修派单闭环 ====================

    /**
     * 报修派单：选择维修人员 → 更新工单 → 自动通知（通知由 WorkOrderNotifier 在事务提交后发送）
     */
    @Transactional(rollbackFor = Exception.class)
    public void dispatch(Long id, Long repairerId, String remark) {
        WorkOrder order = checkTenantOrder(id);
        if (order.getStatus() != 0) {
            throw new ServiceException(500, "仅待处理状态可报修派单");
        }
        Repairer repairer = repairerMapper.selectById(repairerId);
        if (repairer == null || repairer.getStatus() == null || repairer.getStatus() != 1) {
            throw new ServiceException(500, "维修人员不存在或不在岗");
        }
        // 【跨租户加固】派单人与工单必须同租户：防止超管把 A 租户工单派给 B 租户维修人员，
        // 导致维修人员在 H5 端因租户隔离看不到工单。
        if (!order.getTenantId().equals(repairer.getTenantId())) {
            throw new ServiceException(500, "维修人员与工单不属于同一租户，请重新选择");
        }
        // 1. 更新工单
        order.setStatus(1);
        order.setRepairerId(repairerId);
        order.setRepairerName(repairer.getName());
        order.setDispatchTime(LocalDateTime.now());
        order.setRemark(StringUtils.hasText(remark) ? remark : order.getRemark());
        orderMapper.updateById(order);
        addRecord(SecurityUtils.getTenantId(), order.getId(), "dispatch", currentUsername(),
                "报修派单 → " + repairer.getName() + (StringUtils.hasText(remark) ? "，说明：" + remark : ""));

        // 2. 自动通知维修人员（短信+公众号+pushplus，tpl_order_dispatch 模板）
        // 【加固】通知移出数据库事务：事务提交后再发送——
        //   ① 避免 HTTP + 重试（最长数秒）长时间占用数据库事务连接；
        //   ② 通知失败不再回滚派单事务（工单状态已提交，通知失败仅记录日志）；
        //   ③ 事务回滚时不会发出与最终状态不一致的通知。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifier.notifyRepairer(order, repairer);
                } catch (Exception e) {
                    log.error("工单[{}]派单通知发送失败（不影响工单状态）: {}", order.getOrderNo(), e.getMessage());
                }
            }
        });
        pushOrder(order, "dispatch");
    }

    /**
     * 维修开始（维修人员接单后进入维修中）
     */
    @Transactional(rollbackFor = Exception.class)
    public void repairStart(Long id) {
        WorkOrder order = checkTenantOrder(id);
        if (order.getStatus() != 1) {
            throw new ServiceException(500, "仅已派单状态可开始维修");
        }
        order.setStatus(2);
        order.setRepairStartTime(LocalDateTime.now());
        orderMapper.updateById(order);
        addRecord(SecurityUtils.getTenantId(), order.getId(), "repair_start", currentUsername(), "开始维修");
        pushOrder(order, "repair_start");
    }

    /**
     * 完工：回填维修结果。
     * 若租户已发布“工单完工验收”流程（work_order_accept），完工不直接置为已完成，
     * 而是发起审批并保持“维修中（待验收）”，审批通过后由监听器置为已完成；
     * 未发布该流程的租户保持原有直接完工逻辑（向后兼容）。
     *
     * @return 验收审批单；直接完工时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public ApprovalBill complete(Long id, String repairResult) {
        WorkOrder order = checkTenantOrder(id);
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            throw new ServiceException(500, "仅已派单/维修中状态可完工");
        }
        boolean needAccept = workflowService.hasPublishedFlow(FLOW_WORKORDER_ACCEPT);
        if (needAccept) {
            ApprovalBill pending = approvalBillMapper.selectOne(new LambdaQueryWrapper<ApprovalBill>()
                    .eq(ApprovalBill::getBizType, BIZ_WORKORDER_ACCEPT)
                    .eq(ApprovalBill::getBizId, order.getOrderNo())
                    .eq(ApprovalBill::getApprovalStatus, WorkflowService.STATUS_PROCESSING)
                    .orderByDesc(ApprovalBill::getId)
                    .last("limit 1"));
            if (pending != null) {
                throw new ServiceException("该工单已提交验收，请等待审批（审批单 #" + pending.getId() + "）");
            }
            order.setRepairResult(repairResult);
            order.setRepairEndTime(LocalDateTime.now());
            if (order.getRepairStartTime() == null) {
                order.setRepairStartTime(LocalDateTime.now());
            }
            order.setRemark("完工待验收");
            orderMapper.updateById(order);
            addRecord(SecurityUtils.getTenantId(), order.getId(), "complete_submit", currentUsername(),
                    "完工提交验收：" + (StringUtils.hasText(repairResult) ? repairResult : "维修完成"));
            pushOrder(order, "complete_submit");
            ApprovalBill bill = workflowService.start(FLOW_WORKORDER_ACCEPT, BIZ_WORKORDER_ACCEPT,
                    order.getOrderNo(), "工单完工验收 #" + order.getOrderNo());
            log.info("工单完工已提交验收 orderId={} orderNo={} billId={}", order.getId(), order.getOrderNo(), bill.getId());
            return bill;
        }
        order.setStatus(3);
        order.setRepairResult(repairResult);
        order.setRepairEndTime(LocalDateTime.now());
        if (order.getRepairStartTime() == null) {
            order.setRepairStartTime(LocalDateTime.now());
        }
        orderMapper.updateById(order);
        addRecord(SecurityUtils.getTenantId(), order.getId(), "complete", currentUsername(), "完工：" + (StringUtils.hasText(repairResult) ? repairResult : "维修完成"));
        pushOrder(order, "complete");
        return null;
    }

    /**
     * 完工验收审批结果回调（由 WorkOrderAcceptanceListener 调用）
     * 通过 → 工单置为已完成；驳回 → 回到维修中并记录驳回意见，可重新提交完工。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onAcceptance(String orderNo, boolean pass, String comment) {
        if (!StringUtils.hasText(orderNo)) {
            return;
        }
        WorkOrder order = orderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            log.warn("完工验收回调未找到工单 orderNo={}", orderNo);
            return;
        }
        Long tenantId = order.getTenantId();
        if (pass) {
            order.setStatus(3);
            if (order.getRepairEndTime() == null) {
                order.setRepairEndTime(LocalDateTime.now());
            }
            order.setRemark("验收通过");
            orderMapper.updateById(order);
            addRecord(tenantId, order.getId(), "accept_pass", currentUsername(), "验收通过：" + (StringUtils.hasText(comment) ? comment : "维修结果确认"));
            pushOrder(order, "accept_pass");
        } else {
            // 驳回：保持维修中（允许维修人员重新提交完工），仅记录驳回意见
            order.setStatus(2);
            order.setRemark("验收驳回：" + (StringUtils.hasText(comment) ? comment : "维修结果未通过"));
            orderMapper.updateById(order);
            addRecord(tenantId, order.getId(), "accept_reject", currentUsername(), "验收驳回：" + (StringUtils.hasText(comment) ? comment : "请重新处理"));
            pushOrder(order, "accept_reject");
        }
        log.info("工单验收结果同步 orderNo={} pass={} status={}", orderNo, pass, order.getStatus());
    }

    /**
     * 关闭工单（管理员自行处理/无需维修）
     */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id, String remark) {
        WorkOrder order = checkTenantOrder(id);
        if (order.getStatus() != 0 && order.getStatus() != 5) {
            throw new ServiceException(500, "仅待处理/已自动恢复状态可关闭");
        }
        order.setStatus(4);
        orderMapper.updateById(order);
        addRecord(SecurityUtils.getTenantId(), order.getId(), "close", currentUsername(), "关闭工单：" + (StringUtils.hasText(remark) ? remark : "管理员自行处理，无需维修"));
        pushOrder(order, "close");
    }

    // ==================== 内部 ====================

    /**
     * 工单变更实时推送（前端工单列表/大屏看板刷新）
     */
    private void pushOrder(WorkOrder order, String action) {
        try {
            PushWebSocketHandler.broadcast("order", Map.of(
                    "orderId", order.getId(), "orderNo", order.getOrderNo(),
                    "deviceName", order.getDeviceName() == null ? "" : order.getDeviceName(),
                    "status", order.getStatus(), "action", action));
        } catch (Exception ignored) {
            // 推送失败不影响主流程
        }
    }

    private WorkOrder checkTenantOrder(Long id) {
        WorkOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new ServiceException(500, "工单不存在");
        }
        // 【加固】显式租户校验：非超管只能操作本租户工单。
        // selectById 依赖 TenantLine 拦截器自动附加 tenant_id，但未登录/异步线程等无租户上下文时拦截器会跳过，
        // 必须在此显式校验，防止跨租户篡改他人工单（如越权派单/关闭其他租户工单）。
        // tenantId 为 null 属于脏数据，一并按越权拦截（防空指针也防无租户数据被任意操作）。
        if (!SecurityUtils.isSuperAdmin()
                && (order.getTenantId() == null || !order.getTenantId().equals(SecurityUtils.getTenantId()))) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
        return order;
    }

    private void addRecord(Long tenantId, Long orderId, String action, String operator, String content) {
        WorkOrderRecord record = new WorkOrderRecord();
        record.setTenantId(tenantId);
        record.setOrderId(orderId);
        record.setAction(action);
        record.setOperator(operator);
        record.setContent(content);
        recordMapper.insert(record);
    }

    /** 编号生成：前缀 + yyyyMMddHHmmss + 4位随机 */
    private String genNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    /** 当前登录用户名（未登录返回系统） */
    private String currentUsername() {
        try {
            if (SecurityUtils.isAuthenticated() && SecurityUtils.getLoginUser() != null
                    && StringUtils.hasText(SecurityUtils.getLoginUser().getUsername())) {
                return SecurityUtils.getLoginUser().getUsername();
            }
        } catch (Exception ignored) {
        }
        return "系统";
    }
}
