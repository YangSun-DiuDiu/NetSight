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
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.event.AlertEvent;
import com.netsight.modules.alert.entity.NotifyChannel;
import com.netsight.modules.alert.mapper.NotifyChannelMapper;
import com.netsight.modules.alert.mapper.NotificationTemplateMapper;
import com.netsight.modules.alert.service.NotificationChannelService;
import com.netsight.modules.system.entity.SysTenant;
import com.netsight.modules.system.mapper.SysTenantMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.entity.WorkOrderPart;
import com.netsight.modules.workorder.entity.WorkOrderRecord;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import com.netsight.modules.workorder.mapper.WorkOrderPartMapper;
import com.netsight.modules.workorder.mapper.WorkOrderRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
 * 职责：事件联动自动建单（24h 去重）→ 管理员审核 → 报修派单（自动通知维修人员）→ 维修跟进 → 完工关闭
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
    private final NotificationTemplateMapper templateMapper;
    private final NotificationChannelService channelService;
    private final SysTenantMapper tenantMapper;
    private final NotifyChannelMapper notifyChannelMapper;

    /** 状态映射 */
    private static final Map<Integer, String> STATUS_TEXT = Map.of(
            0, "待处理", 1, "已派单", 2, "维修中", 3, "已完成", 4, "已关闭", 5, "已自动恢复");

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
            records.forEach(o -> {
                o.setStatusText(STATUS_TEXT.getOrDefault(o.getStatus(), String.valueOf(o.getStatus())));
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
        order.setStatusText(STATUS_TEXT.getOrDefault(order.getStatus(), String.valueOf(order.getStatus())));
        result.put("order", order);
        result.put("records", recordMapper.selectList(new LambdaQueryWrapper<WorkOrderRecord>()
                .eq(WorkOrderRecord::getOrderId, id).orderByDesc(WorkOrderRecord::getId)));
        result.put("parts", orderPartMapper.selectList(new LambdaQueryWrapper<WorkOrderPart>()
                .eq(WorkOrderPart::getOrderId, id).orderByDesc(WorkOrderPart::getId)));
        return result;
    }

    /**
     * 工单统计（顶部卡片：待处理/已派单/维修中/已完成）
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 0)));
        stats.put("dispatched", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 1)));
        stats.put("repairing", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 2)));
        stats.put("completed", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getStatus, 3)));
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

    // ==================== 事件联动 ====================

    /**
     * 监听告警事件（事件中心发布）：离线/链路异常自动建单，恢复自动归档
     */
    @EventListener
    public void onAlertEvent(AlertEvent alertEvent) {
        EventRecord event = alertEvent.getEvent();
        if (event == null) {
            return;
        }
        try {
            switch (event.getEventType()) {
                case "device_offline", "device_line_abnormal" -> autoCreateFromEvent(event);
                case "device_recovered" -> handleRecover(event);
                default -> { /* 其他事件不联动工单 */ }
            }
        } catch (Exception e) {
            log.error("告警事件[{}]工单联动异常: {}", alertEvent.getEventId(), e.getMessage(), e);
        }
    }

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
        order.setStatus(0);
        orderMapper.insert(order);
        addRecord(event.getTenantId(), order.getId(), "auto_create", "系统", "告警事件[" + event.getEventType() + "]自动生成联系单，等待管理员审核");
        log.info("告警事件[{}]自动生成工单[{}]", event.getId(), order.getOrderNo());
        pushOrder(order, "auto_create");
        return order.getId();
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
     * 报修派单：选择维修人员 → 更新工单 → 自动通知（短信+公众号，复用通道中心）
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
        // 1. 更新工单
        order.setStatus(1);
        order.setRepairerId(repairerId);
        order.setRepairerName(repairer.getName());
        order.setDispatchTime(LocalDateTime.now());
        order.setRemark(StringUtils.hasText(remark) ? remark : order.getRemark());
        orderMapper.updateById(order);
        addRecord(SecurityUtils.getTenantId(), order.getId(), "dispatch", currentUsername(),
                "报修派单 → " + repairer.getName() + (StringUtils.hasText(remark) ? "，说明：" + remark : ""));

        // 2. 自动通知维修人员（短信+公众号，tpl_order_dispatch 模板）
        // 【加固】通知移出数据库事务：事务提交后再发送——
        //   ① 避免 HTTP + 重试（最长数秒）长时间占用数据库事务连接；
        //   ② 通知失败不再回滚派单事务（工单状态已提交，通知失败仅记录日志）；
        //   ③ 事务回滚时不会发出与最终状态不一致的通知。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifyRepairer(order, repairer);
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
     * 完工：回填维修结果，管理员确认后完成
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id, String repairResult) {
        WorkOrder order = checkTenantOrder(id);
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            throw new ServiceException(500, "仅已派单/维修中状态可完工");
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

    // ==================== 内部 ====================

    /**
     * 派单自动通知：复用事件中心通道中心（EventRecord + 临时规则模板 + 通道中心 dispatch）
     */
    private void notifyRepairer(WorkOrder order, Repairer repairer) {
        try {
            NotificationTemplate mainTemplate = templateMapper.selectOne(
                    new LambdaQueryWrapper<NotificationTemplate>()
                            .eq(NotificationTemplate::getTemplateCode, "tpl_order_dispatch")
                            // 【加固】按工单所属租户过滤模板，防止不同租户模板串用（模板为租户级复制数据）
                            .eq(NotificationTemplate::getTenantId, order.getTenantId())
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
            vars.put("deviceName", order.getDeviceName() == null ? "" : order.getDeviceName());
            vars.put("deviceIp", order.getDeviceIp() == null ? "" : order.getDeviceIp());
            vars.put("deviceType", order.getDeviceType() == null ? "" : order.getDeviceType());
            vars.put("deviceLocation", order.getDeviceLocation() == null ? "" : order.getDeviceLocation());
            vars.put("faultDesc", StringUtils.hasText(order.getDescription()) ? order.getDescription()
                    : ("设备" + (order.getFaultType().equals("offline") ? "离线" : "链路异常")));
            vars.put("repairerName", repairer.getName());
            vars.put("tenantName", resolveTenantName(order.getTenantId()));

            // 构造联系人：维修人员只有手机号，封装为 NotificationContact。
            // sms 通道按 mobile 发送；wechat 通道因未绑定 openid 自动跳过（记 warning）；pushplus 忽略接收人按租户 token 群发。
            List<NotificationContact> contacts = new java.util.ArrayList<>();
            if (StringUtils.hasText(repairer.getPhone())) {
                NotificationContact c = new NotificationContact();
                c.setName(repairer.getName());
                c.setMobile(repairer.getPhone());
                contacts.add(c);
            }
            if (contacts.isEmpty()) {
                log.warn("维修人员[{}]未配置手机号，派单通知跳过", repairer.getName());
                return;
            }
            List<Long> channelIds = notifyChannelMapper.selectList(
                    new LambdaQueryWrapper<NotifyChannel>().eq(NotifyChannel::getTenantId, order.getTenantId()).eq(NotifyChannel::getEnabled, 1))
                    .stream().map(NotifyChannel::getId).toList();
            List<NotificationLog> logs = channelService.dispatch(event, rule,
                    contacts, channelIds, vars, null);
            log.info("工单[{}]派单通知已发送，日志{}条", order.getOrderNo(), logs.size());
        } catch (Exception e) {
            log.error("工单[{}]派单通知异常: {}", order.getOrderNo(), e.getMessage(), e);
        }
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
            return tenant == null ? "" : (tenant.getTenantName() == null ? "" : tenant.getTenantName());
        } catch (Exception e) {
            log.warn("解析租户名称失败 tenantId={}: {}", tenantId, e.getMessage());
            return "";
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
