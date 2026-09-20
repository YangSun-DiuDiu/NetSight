package com.netsight.modules.todo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.core.PageResult;
import com.netsight.framework.security.LoginUser;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.mapper.InspectionTaskMapper;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.mapper.RepairerMapper;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一待办服务（demo 4/5）
 * 聚合中心：不建表，聚合查询现有业务表的"待办"数据
 * 待办类型：
 *  - WORK_ORDER_PENDING  工单待处理（status=0，需派单；仅 super_admin/tenant_admin 可见）
 *  - WORK_ORDER_MINE     派给我的维修工单（repairer_id=当前用户手机号对应的维修人员 且 status in (1,2)）
 *  - INSPECTION_TODO     待执行点检任务（assignee_id=当前用户 且 status=0）
 * 多租户：非超管仅本租户（TenantLineInnerInterceptor 已按租户上下文隔离，
 *         本服务对超管不做租户过滤以支持全租户总览）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoService {

    private final WorkOrderMapper workOrderMapper;
    private final InspectionTaskMapper inspectionTaskMapper;
    private final RepairerMapper repairerMapper;

    /** 待办项统一结构 */
    @Data
    public static class TodoItem {
        /** 业务主键（工单 id / 点检任务 id） */
        private Long id;
        /** 待办类型 work_order / inspection */
        private String bizType;
        /** 业务编号（工单号 WO.. / 任务号 INSP..） */
        private String bizNo;
        /** 标题（工单=设备名+故障类型；点检=目标+计划名） */
        private String title;
        /** 关联设备名称 */
        private String deviceName;
        /** 来源（工单 event/manual；点检 计划名） */
        private String source;
        /** 状态文本 */
        private String statusText;
        /** 创建时间 */
        private LocalDateTime createTime;
    }

    /**
     * 待办统计
     */
    public Map<String, Object> stats() {
        LoginUser user = SecurityUtils.getLoginUser();
        boolean admin = isTenantAdmin(user);
        long workOrderPending = 0;
        long workOrderMine = 0;
        long inspectionTodo = 0;
        // 工单待处理（仅可派单角色可见）
        if (admin) {
            workOrderPending = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getStatus, 0));
        }
        // 派给我的维修工单（进行中：1 已派单 / 2 维修中）
        List<Long> repairerIds = currentRepairerIds(user);
        if (!CollectionUtils.isEmpty(repairerIds)) {
            workOrderMine = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .in(WorkOrder::getRepairerId, repairerIds)
                    .in(WorkOrder::getStatus, 1, 2));
        }
        // 待执行点检任务
        if (user != null && user.getUserId() != null) {
            inspectionTodo = inspectionTaskMapper.selectCount(new LambdaQueryWrapper<InspectionTask>()
                    .eq(InspectionTask::getAssigneeId, user.getUserId())
                    .eq(InspectionTask::getStatus, 0));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("workOrderPending", workOrderPending);
        result.put("workOrderMine", workOrderMine);
        result.put("inspectionTodo", inspectionTodo);
        result.put("total", workOrderPending + workOrderMine + inspectionTodo);
        return result;
    }

    /**
     * 待办分页（type=all|work_order|inspection）
     */
    public PageResult<TodoItem> page(String type, long pageNum, long pageSize) {
        List<TodoItem> items = new ArrayList<>();
        if ("work_order".equals(type) || "all".equals(type)) {
            items.addAll(workOrderItems());
        }
        if ("inspection".equals(type) || "all".equals(type)) {
            items.addAll(inspectionItems());
        }
        // 按创建时间倒序
        items.sort(Comparator.comparing(TodoItem::getCreateTime, Comparator.nullsFirst(Comparator.reverseOrder())));
        long total = items.size();
        int from = (int) Math.min(total, (pageNum - 1) * pageSize);
        int to = (int) Math.min(total, pageNum * pageSize);
        List<TodoItem> pageItems = from >= to ? new ArrayList<>() : items.subList(from, to);
        return PageResult.of(total, pageItems);
    }

    /**
     * 工单待办：待处理（可派单角色）+ 派给我的（所有角色）
     */
    private List<TodoItem> workOrderItems() {
        List<TodoItem> items = new ArrayList<>();
        LoginUser user = SecurityUtils.getLoginUser();
        boolean admin = isTenantAdmin(user);
        // 待处理工单（需派单）
        if (admin) {
            List<WorkOrder> pending = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getStatus, 0)
                    .orderByDesc(WorkOrder::getCreateTime));
            for (WorkOrder o : pending) {
                items.add(toWorkOrderItem(o, "待派单"));
            }
        }
        // 派给我的工单
        List<Long> repairerIds = currentRepairerIds(user);
        if (!CollectionUtils.isEmpty(repairerIds)) {
            List<WorkOrder> mine = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                    .in(WorkOrder::getRepairerId, repairerIds)
                    .in(WorkOrder::getStatus, 1, 2)
                    .orderByDesc(WorkOrder::getCreateTime));
            for (WorkOrder o : mine) {
                items.add(toWorkOrderItem(o, o.getStatus() != null && o.getStatus() == 1 ? "已派单" : "维修中"));
            }
        }
        return items;
    }

    private TodoItem toWorkOrderItem(WorkOrder o, String statusText) {
        TodoItem item = new TodoItem();
        item.setId(o.getId());
        item.setBizType("work_order");
        item.setBizNo(o.getOrderNo());
        item.setTitle(o.getDeviceName() == null ? "工单待处理" : o.getDeviceName() + "（" + faultTypeText(o.getFaultType()) + "）");
        item.setDeviceName(o.getDeviceName());
        item.setSource("event".equals(o.getSourceType()) ? "告警自动生成" : "手动创建");
        item.setStatusText(statusText);
        item.setCreateTime(o.getCreateTime());
        return item;
    }

    /**
     * 点检任务待办：待执行且执行人=当前用户
     */
    private List<TodoItem> inspectionItems() {
        List<TodoItem> items = new ArrayList<>();
        LoginUser user = SecurityUtils.getLoginUser();
        if (user == null || user.getUserId() == null) {
            return items;
        }
        List<InspectionTask> tasks = inspectionTaskMapper.selectList(new LambdaQueryWrapper<InspectionTask>()
                .eq(InspectionTask::getAssigneeId, user.getUserId())
                .eq(InspectionTask::getStatus, 0)
                .orderByDesc(InspectionTask::getCreateTime));
        for (InspectionTask t : tasks) {
            TodoItem item = new TodoItem();
            item.setId(t.getId());
            item.setBizType("inspection");
            item.setBizNo(t.getTaskNo());
            item.setTitle("点检任务：" + t.getTargetName() + (t.getPlanName() == null ? "" : "（" + t.getPlanName() + "）"));
            item.setDeviceName(t.getTargetName());
            item.setSource(t.getPlanName() == null ? "手动创建" : t.getPlanName());
            item.setStatusText("待执行");
            item.setCreateTime(t.getCreateTime());
            items.add(item);
        }
        return items;
    }

    /**
     * 是否可派单角色（super_admin / tenant_admin）
     */
    private boolean isTenantAdmin(LoginUser user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().contains("super_admin") || user.getRoles().contains("tenant_admin");
    }

    /**
     * 当前登录用户对应的维修人员库 id 列表
     * 维修人员账号（sys_user）与维修人员库（repairer）通过手机号关联（repairer.phone 唯一）
     * 仅统计在岗（status=1）的维修人员，避免已休假人员仍收到待办
     */
    private List<Long> currentRepairerIds(LoginUser user) {
        if (user == null || user.getPhone() == null) {
            return Collections.emptyList();
        }
        List<Repairer> repairers = repairerMapper.selectList(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getPhone, user.getPhone())
                .eq(Repairer::getStatus, 1));
        if (CollectionUtils.isEmpty(repairers)) {
            return Collections.emptyList();
        }
        return repairers.stream().map(Repairer::getId).collect(Collectors.toList());
    }

    /**
     * 故障类型文本
     */
    private String faultTypeText(String faultType) {
        if ("offline".equals(faultType)) {
            return "设备离线";
        }
        if ("line_abnormal".equals(faultType)) {
            return "链路异常";
        }
        return "手动报修";
    }
}
