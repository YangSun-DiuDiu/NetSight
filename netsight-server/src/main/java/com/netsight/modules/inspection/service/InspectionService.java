package com.netsight.modules.inspection.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.inspection.entity.InspectionItem;
import com.netsight.modules.inspection.entity.InspectionPlan;
import com.netsight.modules.inspection.entity.InspectionRecord;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionItemMapper;
import com.netsight.modules.inspection.mapper.InspectionPlanMapper;
import com.netsight.modules.inspection.mapper.InspectionRecordMapper;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.system.entity.Device;
import com.netsight.modules.system.mapper.DeviceMapper;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 点检巡检服务
 * 点检项库 + 点检计划（周期自动生成任务）+ 点检任务执行 + 点检记录
 * 异常项可联动报修工单（复用 WorkOrderService.addOrder）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final InspectionItemMapper itemMapper;
    private final InspectionPlanMapper planMapper;
    private final InspectionTaskMapper taskMapper;
    private final InspectionRecordMapper recordMapper;
    private final DeviceMapper deviceMapper;
    private final WorkOrderService workOrderService;

    // ==================== 点检项库 ====================

    public PageResult<InspectionItem> pageItem(long pageNum, long pageSize, String itemName) {
        Page<InspectionItem> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InspectionItem> wrapper = new LambdaQueryWrapper<InspectionItem>()
                .like(StringUtils.hasText(itemName), InspectionItem::getItemName, itemName);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionItem::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByAsc(InspectionItem::getSort).orderByDesc(InspectionItem::getId);
        Page<InspectionItem> result = itemMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** 启用的点检项列表（执行任务时选择点检项） */
    public List<InspectionItem> listEnabledItems() {
        LambdaQueryWrapper<InspectionItem> wrapper = new LambdaQueryWrapper<InspectionItem>()
                .eq(InspectionItem::getStatus, 1);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionItem::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByAsc(InspectionItem::getSort).orderByAsc(InspectionItem::getId);
        return itemMapper.selectList(wrapper);
    }

    public void addItem(InspectionItem item) {
        if (!StringUtils.hasText(item.getItemName())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检项名称不能为空");
        }
        if (item.getTenantId() == null) {
            item.setTenantId(SecurityUtils.getTenantId());
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        if (item.getSort() == null) {
            item.setSort(0);
        }
        if (!StringUtils.hasText(item.getResultType())) {
            item.setResultType("check");
        }
        itemMapper.insert(item);
    }

    public void updateItem(InspectionItem item) {
        InspectionItem exist = itemMapper.selectById(item.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检项不存在");
        }
        checkTenantPermission(exist.getTenantId());
        item.setTenantId(exist.getTenantId());
        itemMapper.updateById(item);
    }

    public void deleteItem(Long id) {
        InspectionItem exist = itemMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检项不存在");
        }
        checkTenantPermission(exist.getTenantId());
        itemMapper.deleteById(id);
    }

    // ==================== 点检计划 ====================

    public PageResult<InspectionPlan> pagePlan(long pageNum, long pageSize, String planName, Integer status) {
        Page<InspectionPlan> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InspectionPlan> wrapper = new LambdaQueryWrapper<InspectionPlan>()
                .like(StringUtils.hasText(planName), InspectionPlan::getPlanName, planName)
                .eq(status != null, InspectionPlan::getStatus, status);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionPlan::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(InspectionPlan::getId);
        Page<InspectionPlan> result = planMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    public void addPlan(InspectionPlan plan) {
        if (!StringUtils.hasText(plan.getPlanName())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "计划名称不能为空");
        }
        if (!StringUtils.hasText(plan.getCycleType())) {
            plan.setCycleType("daily");
        }
        if (!StringUtils.hasText(plan.getTargetType())) {
            plan.setTargetType("device");
        }
        if (plan.getStatus() == null) {
            plan.setStatus(1);
        }
        if (plan.getTenantId() == null) {
            plan.setTenantId(SecurityUtils.getTenantId());
        }
        planMapper.insert(plan);
    }

    public void updatePlan(InspectionPlan plan) {
        InspectionPlan exist = planMapper.selectById(plan.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检计划不存在");
        }
        checkTenantPermission(exist.getTenantId());
        plan.setTenantId(exist.getTenantId());
        planMapper.updateById(plan);
    }

    public void deletePlan(Long id) {
        InspectionPlan exist = planMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检计划不存在");
        }
        checkTenantPermission(exist.getTenantId());
        planMapper.deleteById(id);
    }

    /** 手动按计划生成指定日期任务（默认今天；去重靠唯一键）——供 Controller 调用，带登录态租户校验 */
    @Transactional(rollbackFor = Exception.class)
    public int generateTasks(Long planId, LocalDate date) {
        InspectionPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检计划不存在");
        }
        checkTenantPermission(plan.getTenantId());
        return doGenerateTasks(plan, date != null ? date : LocalDate.now());
    }

    /**
     * 实际生成逻辑：不依赖登录态（@Scheduled 定时线程无租户上下文），
     * 目标设备恒按 plan.tenantId 过滤，保证任务与目标设备同租户。
     * 超管手动调用 generateTasks(planId) 时同样按所选计划的租户过滤设备，避免跨租户生成任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public int doGenerateTasks(InspectionPlan plan, LocalDate targetDate) {
        if (plan.getStatus() == null || plan.getStatus() != 1) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "计划已停用，无法生成任务");
        }
        List<Map<String, Object>> targets = resolveTargets(plan);
        int created = 0;
        for (Map<String, Object> t : targets) {
            try {
                InspectionTask task = new InspectionTask();
                task.setTenantId(plan.getTenantId());
                task.setTaskNo(genTaskNo());
                task.setPlanId(plan.getId());
                task.setPlanName(plan.getPlanName());
                task.setTargetId((Long) t.get("id"));
                task.setTargetName((String) t.get("name"));
                task.setTargetType((String) t.get("targetType"));
                task.setTargetLocation((String) t.get("location"));
                task.setAssigneeId(plan.getAssigneeId());
                task.setAssigneeName(plan.getAssigneeName());
                task.setPlanDate(targetDate);
                task.setStatus(0);
                task.setAbnormalCount(0);
                taskMapper.insert(task);
                created++;
            } catch (Exception e) {
                // 唯一键冲突 = 当日已生成，忽略
                log.debug("generateTasks duplicate ignored, planId={}, targetId={}, date={}", plan.getId(), t.get("id"), targetDate);
            }
        }
        return created;
    }

    /** 解析计划目标为 {id,name,targetType,location} 列表（目标设备恒限定 plan.tenantId，任务与设备同租户） */
    private List<Map<String, Object>> resolveTargets(InspectionPlan plan) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> raw = parseStringList(plan.getTargetIds());
        if (raw.isEmpty()) {
            return result;
        }
        if ("device".equals(plan.getTargetType())) {
            List<Long> targetIds = new ArrayList<>();
            for (String s : raw) {
                try {
                    targetIds.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            if (targetIds.isEmpty()) {
                return result;
            }
            List<Device> devices = deviceMapper.selectBatchIds(targetIds);
            for (Device d : devices) {
                // 显式租户校验：计划只允许指向本租户设备（selectBatchIds 在无租户上下文时不做过滤，必须手动过滤）
                if (!plan.getTenantId().equals(d.getTenantId())) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getDeviceName());
                m.put("targetType", "device");
                m.put("location", d.getLocation());
                result.add(m);
            }
        } else {
            // 按设备类型：查询计划所属租户该类型全部设备
            LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
                    .in(Device::getDeviceType, raw)
                    .eq(Device::getTenantId, plan.getTenantId());
            List<Device> devices = deviceMapper.selectList(wrapper);
            for (Device d : devices) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getDeviceName());
                m.put("targetType", "device");
                m.put("location", d.getLocation());
                result.add(m);
            }
        }
        return result;
    }

    // ==================== 点检任务 ====================

    /** 任务列表：管理员看全部，普通角色看"我的任务"（assignee_id=当前用户） */
    public PageResult<InspectionTask> pageTask(long pageNum, long pageSize, String targetName, Integer status, Boolean my) {
        Page<InspectionTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InspectionTask> wrapper = new LambdaQueryWrapper<InspectionTask>()
                .like(StringUtils.hasText(targetName), InspectionTask::getTargetName, targetName)
                .eq(status != null, InspectionTask::getStatus, status);
        boolean isManager = isTaskManager();
        if (Boolean.TRUE.equals(my) || !isManager) {
            // 普通角色只看自己的任务
            wrapper.eq(InspectionTask::getAssigneeId, SecurityUtils.getUserId());
        }
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionTask::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(InspectionTask::getPlanDate).orderByAsc(InspectionTask::getStatus).orderByDesc(InspectionTask::getId);
        Page<InspectionTask> result = taskMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** 任务详情（含点检记录列表） */
    public Map<String, Object> taskDetail(Long id) {
        InspectionTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检任务不存在");
        }
        checkTenantPermission(task.getTenantId());
        List<InspectionRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<InspectionRecord>().eq(InspectionRecord::getTaskId, id));
        Map<String, Object> result = new HashMap<>();
        result.put("task", task);
        result.put("records", records);
        return result;
    }

    /** 开始执行（待执行 → 执行中；仅执行人或管理员可操作） */
    public void startTask(Long id) {
        InspectionTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检任务不存在");
        }
        checkTenantPermission(task.getTenantId());
        // 【加固】归属校验：非管理员只能开始分配给自己的任务（防止代他人执行/越权操作）
        checkTaskAssignee(task);
        if (task.getStatus() == null || task.getStatus() != 0) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "仅待执行任务可开始");
        }
        InspectionTask update = new InspectionTask();
        update.setId(id);
        update.setStatus(1);
        taskMapper.updateById(update);
    }

    /**
     * 提交点检执行：批量写入记录，任务置已完成；异常项可选报修生成工单
     * body: {taskId, remark, repairMode(0/1), items:[{itemId,itemName,checkContent,checkStandard,resultType,result,textResult,remark}]}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitTask(Map<String, Object> body) {
        Long taskId = ((Number) body.get("taskId")).longValue();
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检任务不存在");
        }
        checkTenantPermission(task.getTenantId());
        // 【加固】归属校验：非管理员只能提交分配给自己的任务
        checkTaskAssignee(task);
        if (task.getStatus() == null || (task.getStatus() != 0 && task.getStatus() != 1)) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "仅待执行/执行中任务可提交");
        }
        int repairMode = body.get("repairMode") == null ? 0 : ((Number) body.get("repairMode")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "点检项不能为空");
        }
        String operator = currentUsername();
        Long operatorId = SecurityUtils.getUserId();
        int abnormal = 0;
        Long orderId = null;
        for (Map<String, Object> it : items) {
            InspectionRecord record = new InspectionRecord();
            record.setTenantId(task.getTenantId());
            record.setTaskId(taskId);
            if (it.get("itemId") != null) {
                record.setItemId(((Number) it.get("itemId")).longValue());
            }
            record.setItemName((String) it.getOrDefault("itemName", ""));
            record.setCheckContent((String) it.getOrDefault("checkContent", ""));
            record.setCheckStandard((String) it.getOrDefault("checkStandard", ""));
            record.setResultType((String) it.getOrDefault("resultType", "check"));
            int result = it.get("result") == null ? 0 : ((Number) it.get("result")).intValue();
            record.setResult(result);
            record.setTextResult((String) it.getOrDefault("textResult", ""));
            record.setRemark((String) it.getOrDefault("remark", ""));
            record.setOperatorId(operatorId);
            record.setOperatorName(operator);
            if (result == 1) {
                abnormal++;
                if (orderId == null && repairMode == 1) {
                    // 首个异常项联动报修工单
                    orderId = createRepairOrder(task, (String) it.getOrDefault("itemName", ""),
                            (String) it.getOrDefault("remark", ""), operator);
                }
                record.setOrderId(orderId);
            }
            recordMapper.insert(record);
        }
        InspectionTask update = new InspectionTask();
        update.setId(taskId);
        update.setStatus(2);
        update.setAbnormalCount(abnormal);
        update.setFinishTime(LocalDateTime.now());
        if (body.get("remark") != null) {
            update.setRemark((String) body.get("remark"));
        }
        taskMapper.updateById(update);

        Map<String, Object> result = new HashMap<>();
        result.put("abnormalCount", abnormal);
        result.put("orderId", orderId);
        return result;
    }

    /** 点检异常 → 生成报修工单（复用 WorkOrderService.addOrder，显式传入任务租户） */
    private Long createRepairOrder(InspectionTask task, String itemName, String remark, String operator) {
        WorkOrder order = new WorkOrder();
        order.setSourceType("manual");
        order.setFaultType("manual");
        order.setDeviceName(task.getTargetName());
        order.setDeviceType(task.getTargetType());
        order.setDeviceLocation(task.getTargetLocation());
        order.setSeverity("warning");
        order.setDescription("点检异常报修：点检项【" + itemName + "】" + (StringUtils.hasText(remark) ? "，" + remark : "") + "（任务" + task.getTaskNo() + "）");
        // 【加固】显式传任务所属租户：此前调无参 addOrder 取当前登录租户，超管（租户1）代执行其他租户点检任务时
        // 异常报修工单会错误归属到超管租户（任务租户 ≠ 工单租户，数据错乱）；改传 task.getTenantId() 保证同租户闭环。
        return workOrderService.addOrder(order, task.getTenantId());
    }

    /** 任务统计（待执行/执行中/已完成/逾期/总数/异常数） */
    public Map<String, Object> taskStats() {
        LambdaQueryWrapper<InspectionTask> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionTask::getTenantId, SecurityUtils.getTenantId());
        }
        long total = taskMapper.selectCount(wrapper);
        long pending = taskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getStatus, 0).eq(!SecurityUtils.isSuperAdmin(), InspectionTask::getTenantId, SecurityUtils.getTenantId()));
        long running = taskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getStatus, 1).eq(!SecurityUtils.isSuperAdmin(), InspectionTask::getTenantId, SecurityUtils.getTenantId()));
        long done = taskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getStatus, 2).eq(!SecurityUtils.isSuperAdmin(), InspectionTask::getTenantId, SecurityUtils.getTenantId()));
        long overdue = taskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getStatus, 3).eq(!SecurityUtils.isSuperAdmin(), InspectionTask::getTenantId, SecurityUtils.getTenantId()));
        long abnormal = taskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                .gt(InspectionTask::getAbnormalCount, 0).eq(!SecurityUtils.isSuperAdmin(), InspectionTask::getTenantId, SecurityUtils.getTenantId()));
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("pending", pending);
        result.put("running", running);
        result.put("done", done);
        result.put("overdue", overdue);
        result.put("abnormal", abnormal);
        return result;
    }

    // ==================== 点检记录查询 ====================

    public PageResult<InspectionRecord> pageRecord(long pageNum, long pageSize, Long taskId, Integer result) {
        Page<InspectionRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<InspectionRecord> wrapper = new LambdaQueryWrapper<InspectionRecord>()
                .eq(taskId != null, InspectionRecord::getTaskId, taskId)
                .eq(result != null, InspectionRecord::getResult, result);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(InspectionRecord::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(InspectionRecord::getId);
        Page<InspectionRecord> resultPage = recordMapper.selectPage(page, wrapper);
        return PageResult.of(resultPage.getTotal(), resultPage.getRecords());
    }

    // ==================== 定时任务 ====================

    /** 每日 00:10 扫描启用的计划，按周期生成当天任务（幂等；定时线程无登录态，走 doGenerateTasks 不依赖 SecurityUtils） */
    @Scheduled(cron = "0 10 0 * * ?")
    public void scheduledGenerateTasks() {
        LocalDate today = LocalDate.now();
        List<InspectionPlan> plans = planMapper.selectList(new LambdaQueryWrapper<InspectionPlan>()
                .eq(InspectionPlan::getStatus, 1));
        for (InspectionPlan plan : plans) {
            try {
                if (isDueToday(plan, today)) {
                    // 【加固】不调用 generateTasks(planId)（其内部 checkTenantPermission 依赖登录态，
                    // 定时线程无登录上下文会抛 FORBIDDEN 导致任务丢失），直接走 doGenerateTasks 平台级生成。
                    int created = doGenerateTasks(plan, today);
                    log.info("scheduledGenerateTasks: planId={} date={} created={}", plan.getId(), today, created);
                }
            } catch (Exception e) {
                log.error("scheduledGenerateTasks error, planId={}", plan.getId(), e);
            }
        }
    }

    /** 每日 00:05 将已过计划日期且未完成的待执行任务置为逾期 */
    @Scheduled(cron = "0 5 0 * * ?")
    public void scheduledMarkOverdue() {
        List<InspectionTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<InspectionTask>()
                .lt(InspectionTask::getPlanDate, LocalDate.now())
                .in(InspectionTask::getStatus, 0, 1));
        for (InspectionTask task : tasks) {
            InspectionTask update = new InspectionTask();
            update.setId(task.getId());
            update.setStatus(3);
            taskMapper.updateById(update);
        }
        if (!tasks.isEmpty()) {
            log.info("scheduledMarkOverdue: marked {} tasks overdue", tasks.size());
        }
    }

    /** 判断计划今天是否应生成任务 */
    private boolean isDueToday(InspectionPlan plan, LocalDate today) {
        if (plan.getStartDate() != null && today.isBefore(plan.getStartDate())) {
            return false;
        }
        if (plan.getEndDate() != null && today.isAfter(plan.getEndDate())) {
            return false;
        }
        return switch (plan.getCycleType() == null ? "daily" : plan.getCycleType()) {
            case "daily" -> true;
            case "weekly" -> today.getDayOfWeek().getValue() == 1;
            case "monthly" -> today.getDayOfMonth() == 1;
            default -> false;
        };
    }

    // ==================== 工具方法 ====================

    private String genTaskNo() {
        return "INSP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%02d", ThreadLocalRandom.current().nextInt(100));
    }

    /** 解析 JSON 字符串数组为字符串列表：["network","camera"] -> [network, camera] */
    private List<String> parseStringList(String json) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return result;
        }
        String s = json.replace("[", "").replace("]", "").replace("\"", "").replace(" ", "");
        for (String part : s.split(",")) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private List<Long> parseLongList(String json) {
        List<Long> result = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return result;
        }
        String s = json.replace("[", "").replace("]", "").replace("\"", "").replace(" ", "");
        for (String part : s.split(",")) {
            if (StringUtils.hasText(part)) {
                try {
                    result.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private String currentUsername() {
        try {
            return SecurityUtils.getLoginUser().getUsername();
        } catch (Exception e) {
            return "系统";
        }
    }

    /**
     * 是否为点检管理角色（超管 / 租户管理员 / 具备 inspection:task:list 权限者）。
     * 管理角色可查看全部任务并代执行；普通角色只能操作分配给自己的任务。
     */
    private boolean isTaskManager() {
        return SecurityUtils.isSuperAdmin()
                || (SecurityUtils.getLoginUser().getRoles() != null && SecurityUtils.getLoginUser().getRoles().contains("tenant_admin"))
                || (SecurityUtils.getLoginUser().getPermissions() != null && SecurityUtils.getLoginUser().getPermissions().contains("inspection:task:list"));
    }

    /**
     * 点检任务归属校验：非管理员仅允许操作 assignee_id = 当前用户 的任务
     */
    private void checkTaskAssignee(InspectionTask task) {
        if (!isTaskManager() && !SecurityUtils.getUserId().equals(task.getAssigneeId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }

    /** 数据隔离校验：非超管不能操作其他租户数据（targetTenantId 为 null 属脏数据，一并拦截防 NPE/越权） */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin()
                && (targetTenantId == null || !targetTenantId.equals(SecurityUtils.getTenantId()))) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }
}
