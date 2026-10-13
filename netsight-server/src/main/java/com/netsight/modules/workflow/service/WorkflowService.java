package com.netsight.modules.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import com.netsight.modules.workflow.mapper.ApprovalBillMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.HisTask;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.entity.User;
import org.dromara.warm.flow.core.enums.FlowStatus;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.enums.UserType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.HisTaskService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.entity.FlowHisTask;
import org.dromara.warm.flow.orm.entity.FlowUser;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批工作流服务
 *
 * 统一封装 Warm-Flow 引擎能力（发起/审批/撤销/待办/历史），
 * 以 approval_bill（统一审批单）为业务载体，屏蔽引擎细节。
 *
 * 核心数据流：
 * 发起 → InsService.start(flowCode, FlowParams) 生成 flow_instance，
 *       同时落 approval_bill（bizId/流程实例ID 绑定）；
 * 审批 → TaskService.getByInsId 取当前待办任务 → TaskService.skip(taskId, FlowParams) 流转；
 * 状态 → 依据 Instance.flowStatus（FlowStatus 枚举）同步 approval_bill.approvalStatus。
 *
 * 流程模板租户隔离（方案 B）：
 * 超管在租户1维护预置流程定义；新租户注册时通过 copyFlowTemplatesToTenant
 * 将 flow_definition / flow_node / flow_skip 三表复制到新租户，
 * 租户可自行调整本租户流程。复制走原生 JDBC，绕过 MP 租户拦截器。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowService {

    /** 审批单状态 */
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELED = "canceled";

    /** 预置流程模板默认租户（租户1） */
    public static final long DEFAULT_TEMPLATE_TENANT = 1L;

    private final DefService defService;
    private final InsService insService;
    private final TaskService taskService;
    private final HisTaskService hisTaskService;
    private final UserService flowUserService;
    private final org.dromara.warm.flow.orm.mapper.FlowUserMapper flowUserMapper;
    private final ApprovalBillMapper approvalBillMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 流程定义 ====================

    /**
     * 查询全部流程定义（含未发布，前端按 flowStatus 区分）
     * 租户隔离：flow_definition 表由引擎 TenantHandler 过滤 tenant_id
     */
    public List<Definition> listDefinitions() {
        // 多租户隔离（方案B）：仅返回当前租户的流程定义，防止越权看到其他租户模板
        FlowDefinition query = new FlowDefinition();
        query.setTenantId(String.valueOf(SecurityUtils.getTenantId()));
        return defService.list(query);
    }

    /**
     * 按流程编码查询已发布定义（发起前校验）
     */
    public Definition getPublishedByFlowCode(String flowCode) {
        Definition definition = defService.getPublishByFlowCode(flowCode);
        if (definition == null) {
            throw new ServiceException("流程定义未找到或未发布：" + flowCode);
        }
        return definition;
    }

    // ==================== 发起 ====================

    /**
     * 发起流程并登记统一审批单
     *
     * @param flowCode 流程编码（设计器创建并发布后获得）
     * @param bizType  业务类型（out_stock / in_stock / workorder_accept 等）
     * @param bizId    业务单号（出库单号/工单号等，业务侧唯一）
     * @param title    审批标题
     * @return 审批单（含 processInstanceId）
     */
    public ApprovalBill start(String flowCode, String bizType, String bizId, String title) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new ServiceException(ResultCode.UNAUTHORIZED);
        }
        if (!StringUtils.hasText(flowCode) || !StringUtils.hasText(bizId)) {
            throw new ServiceException("flowCode 与 bizId 不能为空");
        }
        // 发起前校验流程已发布
        getPublishedByFlowCode(flowCode);

        // 1. 引擎发起：变量携带业务上下文，供后续节点/监听器使用
        Map<String, Object> variable = new HashMap<>();
        variable.put("businessId", bizId);
        variable.put("bizType", bizType);
        variable.put("title", title);
        variable.put("applyUserId", userId);
        FlowParams params = FlowParams.build()
                .flowCode(flowCode)
                .handler(String.valueOf(userId))
                .variable(variable);
        Instance instance = insService.start(flowCode, params);
        if (instance == null || instance.getId() == null) {
            throw new ServiceException("流程发起失败，引擎未返回实例");
        }
        // 引擎 1.8.9 运行时表（flow_instance/flow_task/flow_user）写入时不填充 tenant_id，
        // 而引擎所有查询都会按 TenantHandler 追加租户过滤 → 需发起后立即回填，否则待办/流转查不到。
        String tenant = String.valueOf(SecurityUtils.getTenantId());
        jdbcTemplate.update("UPDATE flow_instance SET tenant_id = ? WHERE id = ?", tenant, instance.getId());
        jdbcTemplate.update("UPDATE flow_task SET tenant_id = ? WHERE instance_id = ?", tenant, instance.getId());
        jdbcTemplate.update("UPDATE flow_user SET tenant_id = ? WHERE associated IN (SELECT id FROM flow_task WHERE instance_id = ?)",
                tenant, instance.getId());

        // 2. 登记统一审批单
        ApprovalBill bill = new ApprovalBill();
        bill.setTenantId(SecurityUtils.getTenantId());
        bill.setBizType(bizType);
        bill.setBizId(bizId);
        bill.setFlowCode(flowCode);
        bill.setFlowName(instance.getFlowName());
        bill.setProcessInstanceId(instance.getId());
        bill.setApprovalStatus(STATUS_PROCESSING);
        bill.setApplyUserId(userId);
        bill.setApplyUserName(SecurityUtils.getLoginUser().getRealName());
        bill.setCurrentNode(instance.getNodeName());
        bill.setTitle(StringUtils.hasText(title) ? title : (bizType + "审批 " + bizId));
        approvalBillMapper.insert(bill);

        log.info("流程发起成功 flowCode={} bizType={} bizId={} instanceId={} billId={}",
                flowCode, bizType, bizId, instance.getId(), bill.getId());
        return bill;
    }

    // ==================== 审批 ====================

    /**
     * 审批动作（通过 / 驳回）
     *
     * @param billId  审批单ID
     * @param pass    是否通过
     * @param comment 审批意见
     */
    public ApprovalBill approve(Long billId, boolean pass, String comment) {
        ApprovalBill bill = getBill(billId);
        if (!STATUS_PROCESSING.equals(bill.getApprovalStatus())) {
            throw new ServiceException("当前单据状态不可审批：" + bill.getApprovalStatus());
        }
        // 当前待办任务（getByInsId 返回未结束节点的任务列表）
        List<Task> tasks = taskService.getByInsId(bill.getProcessInstanceId());
        if (tasks == null || tasks.isEmpty()) {
            throw new ServiceException("当前无待办任务，流程可能已结束");
        }
        Task task = tasks.get(0);
        log.info("[wf-debug] approve billId={} instanceId={} taskId={} nodeName={}",
                billId, bill.getProcessInstanceId(), task.getId(), task.getNodeName());
        try {
            List<User> users = FlowEngine.userService().listByAssociatedAndTypes(task.getId(), new String[0]);
            log.info("[wf-debug] userList size={} -> {}", users == null ? 0 : users.size(),
                    users == null ? "" : users.stream().map(u -> u.getProcessedBy() + "/" + u.getType()).collect(java.util.stream.Collectors.toList()));
        } catch (Exception ex) {
            log.info("[wf-debug] userList query ERR", ex);
        }

        // 角色型审批认领：将任务处理人绑定为当前审批人（flow_user.processed_by=当前用户ID）
        // 引擎 1.8.9 cooperate 校验硬比较 processed_by == handler(用户ID)，
        // 而按角色发起时 processed_by 存角色标识(如 tenant_admin)，需在审批前落具体用户，否则报"无权限"。
        jdbcTemplate.update(
                "UPDATE flow_user SET processed_by = ?, update_time = NOW() " +
                "WHERE associated = ? AND type = ? AND del_flag = '0'",
                String.valueOf(SecurityUtils.getUserId()), task.getId(), UserType.APPROVAL.getKey());

        // 驳回语义：引擎 1.8.9 禁止 REJECT 退回到 START 节点（"禁止退回到第一个节点"），
        // 且本流程 start→approve→end 无上一审批节点可回。
        // 因此驳回走官方终止接口 terminationByInsId：实例置 REJECT 终止、单据置"已驳回"，
        // 发起人可在前端对同一 bizId 重新提交（重新 start 生成新实例，保留历史审批链）。
        if (!pass) {
            FlowParams params = FlowParams.build()
                    .handler(String.valueOf(SecurityUtils.getUserId()))
                    .message(comment);
            taskService.terminationByInsId(bill.getProcessInstanceId(), params);
            bill.setApprovalStatus(STATUS_REJECTED);
            bill.setCurrentNode("已驳回");
            bill.setRemark(comment);
            approvalBillMapper.updateById(bill);
            // 发布审批结果事件（业务存档：出库/入库申请单状态同步）
            publishApprovalEvent(bill, false, comment);
            log.info("审批驳回 billId={} instanceId={} 已终止，等待重新提交", billId, bill.getProcessInstanceId());
            return bill;
        }

        // 引擎流转（通过）：skipType=PASS；handler=审批人；message=审批意见
        // ignoreCooperate(true)：单审批人节点跳过会签比例校验（cooperate 对 nodeRatio 为空的
        // 单审批节点会走会签百分比计算导致 NPE/误判；单人审批无需会签语义）
        FlowParams params = FlowParams.build()
                .skipType(SkipType.PASS.getKey())
                .handler(String.valueOf(SecurityUtils.getUserId()))
                .message(comment)
                .ignoreCooperate(true);
        Instance instance = taskService.skip(task.getId(), params);

        // 同步审批单状态（依据引擎流程状态）
        String flowStatus = instance == null ? null : instance.getFlowStatus();
        if (flowStatus != null && (FlowStatus.isFinished(flowStatus)
                || FlowStatus.PASS.getKey().equals(flowStatus))) {
            bill.setApprovalStatus(STATUS_APPROVED);
        } else if (flowStatus != null && FlowStatus.REJECT.getKey().equals(flowStatus)) {
            bill.setApprovalStatus(STATUS_REJECTED);
        } else {
            bill.setApprovalStatus(STATUS_PROCESSING);
        }
        bill.setCurrentNode(instance == null ? bill.getCurrentNode() : instance.getNodeName());
        bill.setRemark(comment);
        approvalBillMapper.updateById(bill);

        log.info("审批操作 billId={} pass={} flowStatus={} instanceId={}",
                billId, pass, flowStatus, bill.getProcessInstanceId());
        // 发布审批结果事件（业务存档：出库/入库申请单状态同步）
        publishApprovalEvent(bill, true, comment);
        return bill;
    }

    /**
     * 发布审批结果事件（供业务模块监听做审批存档）
     */
    private void publishApprovalEvent(ApprovalBill bill, boolean pass, String comment) {
        try {
            eventPublisher.publishEvent(new WorkflowApprovalEvent(
                    bill.getBizType(), bill.getBizId(), pass, comment, bill.getApprovalStatus()));
        } catch (Exception e) {
            // 事件发布失败不影响审批主流程
            log.error("审批事件发布失败 billId={}: {}", bill.getId(), e.getMessage());
        }
    }

    /**
     * 撤销流程（发起人撤回未审批单据）
     */
    public ApprovalBill revoke(Long billId) {
        ApprovalBill bill = getBill(billId);
        if (!STATUS_PROCESSING.equals(bill.getApprovalStatus())) {
            throw new ServiceException("当前单据状态不可撤销：" + bill.getApprovalStatus());
        }
        FlowParams params = FlowParams.build().handler(String.valueOf(SecurityUtils.getUserId()));
        taskService.terminationByInsId(bill.getProcessInstanceId(), params);
        bill.setApprovalStatus(STATUS_CANCELED);
        approvalBillMapper.updateById(bill);
        log.info("流程撤销 billId={} instanceId={}", billId, bill.getProcessInstanceId());
        return bill;
    }

    // ==================== 查询 ====================

    /**
     * 我的待办：flow_user 中当前用户相关（按用户ID或按角色标识）且 type=审批人(APPROVAL) 的关联任务
     *
     * 引擎任务-审批人模型（warm-flow 1.8.9）：
     * - flow_task 不直接存审批人；由 flow_user(processed_by, associated=任务ID, type=APPROVAL) 关联；
     * - 审批人配置为【用户】时 processed_by=用户ID；配置为【角色】时 processed_by=角色标识(如 tenant_admin)。
     * 因此待办查询需同时匹配 用户ID 与 当前用户拥有的角色标识，才能覆盖"按角色选审批人"场景。
     */
    public List<Map<String, Object>> myTodo() {
        Long userId = SecurityUtils.getUserId();
        List<Map<String, Object>> result = new ArrayList<>();
        if (userId == null) {
            return result;
        }
        List<String> roles = SecurityUtils.getLoginUser().getRoles() == null
                ? List.of() : SecurityUtils.getLoginUser().getRoles().stream().toList();
        // 超级管理员拥有全部角色能力（与 NamsPermissionHandler 一致）：可审批任意角色节点
        if (roles.contains("super_admin")) {
            roles = List.of("super_admin", "tenant_admin", "ops", "repairer", "device_admin", "warehouse_admin");
        }
        // 匹配身份集合：用户ID + 其角色标识（角色为审批人时 processed_by 存角色 key）
        List<String> identities = new ArrayList<>();
        identities.add(String.valueOf(userId));
        identities.addAll(roles);

        // 用 Mapper + LambdaQueryWrapper 做 IN 查询（引擎 list(T) 是等值语义，逗号串无法命中）
        // 多租户隔离（方案B）：flow_user 在 MP 拦截器忽略表中，需显式按当前租户过滤，防止跨租户待办越权
        String tenantId = String.valueOf(SecurityUtils.getTenantId());
        List<FlowUser> users = flowUserMapper.selectList(new LambdaQueryWrapper<FlowUser>()
                .eq(FlowUser::getType, UserType.APPROVAL.getKey())
                .eq(FlowUser::getTenantId, tenantId)
                .in(FlowUser::getProcessedBy, identities)
                .eq(FlowUser::getDelFlag, "0"));
        if (users == null || users.isEmpty()) {
            return result;
        }
        List<Long> taskIds = users.stream().map(User::getAssociated).toList();
        List<Task> tasks = taskService.getByIds(taskIds);
        if (tasks == null || tasks.isEmpty()) {
            return result;
        }
        for (Task task : tasks) {
            Map<String, Object> item = buildTodoItem(task);
            result.add(item);
        }
        return result;
    }

    /**
     * 我发起的审批单（按发起人查统一审批单，倒序）
     */
    public List<ApprovalBill> myStarted() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return List.of();
        }
        return approvalBillMapper.selectList(new LambdaQueryWrapper<ApprovalBill>()
                .eq(ApprovalBill::getApplyUserId, userId)
                .orderByDesc(ApprovalBill::getCreateTime));
    }

    /**
     * 审批单详情 + 引擎流程历史轨迹
     */
    public Map<String, Object> billDetail(Long billId) {
        ApprovalBill bill = getBill(billId);
        Map<String, Object> detail = new HashMap<>();
        detail.put("bill", bill);
        // 流程历史（flow_his_task 按实例ID倒序）
        FlowHisTask hisQuery = new FlowHisTask();
        hisQuery.setInstanceId(bill.getProcessInstanceId());
        List<HisTask> history = hisTaskService.list(hisQuery);
        detail.put("history", history == null ? List.of() : history);
        return detail;
    }

    // ==================== 内部方法 ====================

    private ApprovalBill getBill(Long billId) {
        ApprovalBill bill = approvalBillMapper.selectById(billId);
        if (bill == null) {
            throw new ServiceException("审批单不存在：" + billId);
        }
        return bill;
    }

    /**
     * 流程模板租户复制（方案 B：租户隔离 + 注册复制）
     *
     * 将 sourceTenantId 租户的全部已发布/未发布流程定义（flow_definition）
     * 连同节点（flow_node）与跳转（flow_skip）复制到 targetTenantId 租户。
     *
     * 实现要点：
     * 1. flow_* 三表 id 为手动分配（无自增），新行统一用 IdWorker 雪花算法生成新 id；
     * 2. flow_skip 通过 now_node_code / next_node_code（字符串节点编码）关联节点，
     *    不引用 node 主键 → 复制 skip 无需节点 id 重映射；
     * 3. 复制走原生 JDBC（JdbcTemplate），显式指定 tenant_id，
     *    绕过 MP 租户拦截器（flow_* 表已在 IGNORE_TABLES 豁免）；
     * 4. 事务内完成，任一步失败整体回滚；复制前先清空目标租户旧模板（幂等，支持重复注册/重试）。
     *
     * @return 复制的流程定义数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int copyFlowTemplatesToTenant(Long sourceTenantId, Long targetTenantId) {
        String srcTenant = String.valueOf(sourceTenantId);
        String tgtTenant = String.valueOf(targetTenantId);
        LocalDateTime now = LocalDateTime.now();

        // 幂等：先清空目标租户已有模板（防止重复复制/重试产生脏数据）
        jdbcTemplate.update("DELETE FROM flow_skip WHERE tenant_id = ?", tgtTenant);
        jdbcTemplate.update("DELETE FROM flow_node WHERE tenant_id = ?", tgtTenant);
        jdbcTemplate.update("DELETE FROM flow_definition WHERE tenant_id = ?", tgtTenant);

        // 1. 读取源租户全部流程定义
        List<Map<String, Object>> defs = jdbcTemplate.queryForList(
                "SELECT * FROM flow_definition WHERE tenant_id = ? AND del_flag = '0'", srcTenant);

        int copied = 0;
        for (Map<String, Object> def : defs) {
            long oldDefId = ((Number) def.get("id")).longValue();
            long newDefId = IdWorker.getId();

            // 2. 复制流程定义（显式新 id + 目标租户）
            jdbcTemplate.update(
                    "INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish, " +
                    "form_custom, form_path, activity_status, listener_type, listener_path, ext, " +
                    "create_time, create_by, update_time, update_by, del_flag, tenant_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    newDefId,
                    def.get("flow_code"), def.get("flow_name"), def.get("model_value"), def.get("category"),
                    def.get("version"), def.get("is_publish"), def.get("form_custom"), def.get("form_path"),
                    def.get("activity_status"), def.get("listener_type"), def.get("listener_path"), def.get("ext"),
                    now, "system", now, "system", def.get("del_flag"), tgtTenant);

            // 3. 复制流程节点（definition_id 指向新定义 id；node 自身 id 重新生成）
            List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                    "SELECT * FROM flow_node WHERE definition_id = ? AND tenant_id = ?", oldDefId, srcTenant);
            for (Map<String, Object> node : nodes) {
                jdbcTemplate.update(
                        "INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, " +
                        "node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, " +
                        "version, create_time, create_by, update_time, update_by, ext, del_flag, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(),
                        node.get("node_type"), newDefId, node.get("node_code"), node.get("node_name"),
                        node.get("permission_flag"), node.get("node_ratio"), node.get("coordinate"),
                        node.get("any_node_skip"), node.get("listener_type"), node.get("listener_path"),
                        node.get("form_custom"), node.get("form_path"), node.get("version"),
                        now, "system", now, "system", node.get("ext"), node.get("del_flag"), tgtTenant);
            }

            // 4. 复制跳转（node_code 字符串关联，无需节点 id 重映射）
            List<Map<String, Object>> skips = jdbcTemplate.queryForList(
                    "SELECT * FROM flow_skip WHERE definition_id = ? AND tenant_id = ?", oldDefId, srcTenant);
            for (Map<String, Object> skip : skips) {
                jdbcTemplate.update(
                        "INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, " +
                        "next_node_type, skip_name, skip_type, skip_condition, coordinate, " +
                        "create_time, create_by, update_time, update_by, del_flag, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(),
                        newDefId, skip.get("now_node_code"), skip.get("now_node_type"),
                        skip.get("next_node_code"), skip.get("next_node_type"), skip.get("skip_name"),
                        skip.get("skip_type"), skip.get("skip_condition"), skip.get("coordinate"),
                        now, "system", now, "system", skip.get("del_flag"), tgtTenant);
            }
            copied++;
        }

        log.info("流程模板复制完成: 源租户[{}] -> 目标租户[{}], 共 {} 个流程定义", sourceTenantId, targetTenantId, copied);
        return copied;
    }

    /**
     * 组装待办条目：任务基础信息 + 对应审批单标题
     */
    private Map<String, Object> buildTodoItem(Task task) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskId", task.getId());
        item.put("instanceId", task.getInstanceId());
        item.put("businessId", task.getBusinessId());
        item.put("nodeCode", task.getNodeCode());
        item.put("nodeName", task.getNodeName());
        item.put("flowName", task.getFlowName());
        item.put("flowStatus", task.getFlowStatus());
        item.put("createTime", task.getCreateTime());
        // 关联审批单标题
        if (task.getInstanceId() != null) {
            ApprovalBill bill = approvalBillMapper.selectOne(new LambdaQueryWrapper<ApprovalBill>()
                    .eq(ApprovalBill::getProcessInstanceId, task.getInstanceId())
                    .last("limit 1"));
            item.put("billId", bill == null ? null : bill.getId());
            item.put("title", bill == null ? task.getBusinessId() : bill.getTitle());
            item.put("bizType", bill == null ? null : bill.getBizType());
            item.put("applyUserName", bill == null ? null : bill.getApplyUserName());
        }
        return item;
    }
}
