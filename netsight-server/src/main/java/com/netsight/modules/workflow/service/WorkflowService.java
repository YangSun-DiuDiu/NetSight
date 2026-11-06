package com.netsight.modules.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.enums.ApprovalAction;
import com.netsight.modules.workflow.enums.WorkflowStatus;
import com.netsight.modules.workflow.event.WorkflowApprovalEvent;
import com.netsight.modules.workflow.mapper.ApprovalBillMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.entity.User;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.enums.UserType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.InsService;
import org.dromara.warm.flow.core.service.TaskService;
import org.dromara.warm.flow.core.service.UserService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
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

    /**
     * 当前租户是否已发布指定流程（用于业务侧“可选审批”分支判断，未配置时保持原逻辑）
     */
    public boolean hasPublishedFlow(String flowCode) {
        if (!StringUtils.hasText(flowCode)) {
            return false;
        }
        try {
            return defService.getPublishByFlowCode(flowCode) != null;
        } catch (Exception e) {
            log.warn("流程定义查询失败 flowCode={}: {}", flowCode, e.getMessage());
            return false;
        }
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
        // 发起前校验流程已发布，同时取流程名用于审批单冗余展示
        Definition definition = getPublishedByFlowCode(flowCode);

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
        // 引擎 1.8.9 运行时表写入时不填 tenant_id，而引擎查询会按 TenantHandler 追加租户过滤，
        // 因此每次流转后都要把实例、任务、审批人、历史四张表补齐（只填 NULL，幂等）。
        syncRuntimeTenant(instance.getId(), SecurityUtils.getTenantId());

        // 2. 登记统一审批单
        ApprovalBill bill = new ApprovalBill();
        bill.setTenantId(SecurityUtils.getTenantId());
        bill.setBizType(bizType);
        bill.setBizId(bizId);
        bill.setFlowCode(flowCode);
        bill.setFlowName(definition.getFlowName() != null ? definition.getFlowName() : instance.getFlowName());
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
            syncRuntimeTenant(bill.getProcessInstanceId(), bill.getTenantId());
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
        // 新任务/新审批人由引擎写入，同样可能缺租户，流转后立即补齐
        syncRuntimeTenant(bill.getProcessInstanceId(), bill.getTenantId());

        // 同步审批单状态：只有整条流程结束（8）才算通过；
        // 2 PASS 在多节点流程里只是"当前节点通过"，不能提前把单据置为已通过。
        String flowStatus = instance == null ? null : instance.getFlowStatus();
        WorkflowStatus status = WorkflowStatus.resolve(flowStatus, bill.getApprovalStatus());
        bill.setApprovalStatus(status.getCode());
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
        syncRuntimeTenant(bill.getProcessInstanceId(), bill.getTenantId());
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
     * 我审批过的（历史已办）
     *
     * 数据源：flow_his_task，按 approver=当前用户ID 过滤，
     * 关联 approval_bill（process_instance_id = instance_id）拿业务标题/发起人/当前状态。
     * 按审批时间倒序，分页返回。
     *
     * @param pageNum  页码（从1开始）
     * @param pageSize 每页条数
     */
    public Map<String, Object> myDone(int pageNum, int pageSize) {
        Long userId = SecurityUtils.getUserId();
        Map<String, Object> result = new HashMap<>();
        if (userId == null) {
            result.put("rows", List.of());
            result.put("total", 0);
            return result;
        }
        Long tenantId = SecurityUtils.getTenantId();
        String approver = String.valueOf(userId);
        int offset = (pageNum - 1) * pageSize;

        // 租户以 approval_bill 为权威（历史表 tenant_id 由引擎写入，可能为空）；
        // node_type <> 0 排除发起节点：发起节点的 approver 也记发起人，混进来会变成"我审批了我自己"。
        String from = "FROM flow_his_task h " +
                "INNER JOIN approval_bill b ON b.process_instance_id = h.instance_id " +
                "WHERE h.approver = ? AND b.tenant_id = ? AND h.node_type <> 0 " +
                "AND h.del_flag = '0' AND b.del_flag = '0'";

        // 总数
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + from,
                Integer.class, approver, tenantId);
        result.put("total", total == null ? 0 : total);

        if (total == null || total == 0) {
            result.put("rows", List.of());
            return result;
        }

        // 分页查询 + 关联 approval_bill
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT h.id AS hisId, h.instance_id AS instanceId, h.node_code AS nodeCode, " +
                "       h.node_name AS nodeName, h.node_type + 0 AS nodeType, " +
                "       h.skip_type AS skipType, h.flow_status AS flowStatus, h.message AS myComment, " +
                "       h.create_time AS approveTime, " +
                "       b.id AS billId, b.biz_id AS bizId, b.title AS title, b.flow_name AS flowName, " +
                "       b.flow_code AS flowCode, b.biz_type AS bizType, b.apply_user_name AS applyUserName, " +
                "       b.approval_status AS currentBillStatus " +
                from +
                " ORDER BY h.create_time DESC LIMIT ? OFFSET ?",
                approver, tenantId, pageSize, offset);

        // 动作/状态统一在本层翻译，前端不再自行判断 skipType
        for (Map<String, Object> row : rows) {
            decorateDoneRow(row);
        }
        result.put("rows", rows);
        return result;
    }

    /** 为历史已办行补齐动作与状态的展示字段（保留旧字段，向后兼容） */
    private void decorateDoneRow(Map<String, Object> row) {
        Object nodeTypeObj = row.get("nodeType");
        Integer nodeType = nodeTypeObj instanceof Number ? ((Number) nodeTypeObj).intValue() : null;
        String flowStatus = row.get("flowStatus") == null ? null : String.valueOf(row.get("flowStatus"));
        String billStatus = row.get("currentBillStatus") == null ? null : String.valueOf(row.get("currentBillStatus"));

        ApprovalAction action = ApprovalAction.resolve(nodeType, flowStatus, billStatus);
        row.put("actionCode", action.getCode());
        row.put("actionText", action.getText());
        row.put("actionTag", action.getTag());

        WorkflowStatus status = WorkflowStatus.of(billStatus);
        if (status == null) {
            status = WorkflowStatus.PROCESSING;
        }
        row.put("currentStatusText", status.getText());
        row.put("currentStatusTag", status.getTag());

        if (row.get("flowName") == null || String.valueOf(row.get("flowName")).isEmpty()) {
            row.put("flowName", row.get("flowCode"));
        }
    }

    /**
     * 审批单详情 + 引擎流程历史轨迹
     */
    public Map<String, Object> billDetail(Long billId) {
        ApprovalBill bill = getBill(billId);
        Map<String, Object> detail = new HashMap<>();
        detail.put("bill", bill);
        WorkflowStatus status = WorkflowStatus.of(bill.getApprovalStatus());
        if (status == null) {
            status = WorkflowStatus.PROCESSING;
        }
        detail.put("statusCode", status.getCode());
        detail.put("statusText", status.getText());
        detail.put("statusTag", status.getTag());

        // 流程历史：直接用 JDBC 按实例ID读取。
        // 引擎 list 会走租户过滤，而历史行的 tenant_id 可能为空，会导致轨迹查不到。
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT id, instance_id AS instanceId, node_code AS nodeCode, node_name AS nodeName, " +
                "       node_type + 0 AS nodeType, approver, skip_type AS skipType, flow_status AS flowStatus, " +
                "       message, create_time AS createTime " +
                "FROM flow_his_task WHERE instance_id = ? AND del_flag = '0' " +
                "ORDER BY create_time ASC, id ASC",
                bill.getProcessInstanceId());
        for (Map<String, Object> row : history) {
            Object nodeTypeObj = row.get("nodeType");
            Integer nodeType = nodeTypeObj instanceof Number ? ((Number) nodeTypeObj).intValue() : null;
            String flowStatus = row.get("flowStatus") == null ? null : String.valueOf(row.get("flowStatus"));
            ApprovalAction action = ApprovalAction.resolve(nodeType, flowStatus, bill.getApprovalStatus());
            row.put("actionCode", action.getCode());
            row.put("actionText", action.getText());
            row.put("actionTag", action.getTag());
        }
        detail.put("history", history);
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
     * 查询预置模板库（租户1）的全部流程定义，供超管勾选后复制到目标租户。
     * 超管权限下调用，返回所有租户1的模板（含草稿）。
     */
    public List<Map<String, Object>> listSourceTemplates() {
        return jdbcTemplate.queryForList(
                "SELECT id, flow_code, flow_name, category, version, is_publish, create_time " +
                "FROM flow_definition WHERE tenant_id = ? AND del_flag = '0' " +
                "ORDER BY flow_code",
                String.valueOf(DEFAULT_TEMPLATE_TENANT));
    }

    /**
     * 选择性复制流程模板到目标租户（方案 B 改造版）
     *
     * @param targetTenantId 目标租户ID
     * @param flowCodes      要复制的流程编码列表（来自租户1）
     * @param conflictStrategy 冲突策略：overwrite=覆盖目标租户同编码模板；skip=跳过
     * @return 实际复制的流程数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int copyFlowTemplatesSelectively(Long targetTenantId, List<String> flowCodes, String conflictStrategy) {
        String srcTenant = String.valueOf(DEFAULT_TEMPLATE_TENANT);
        String tgtTenant = String.valueOf(targetTenantId);
        LocalDateTime now = LocalDateTime.now();

        if (flowCodes == null || flowCodes.isEmpty()) {
            throw new ServiceException("请选择要复制的流程模板");
        }

        int copied = 0;
        int skipped = 0;

        for (String flowCode : flowCodes) {
            // 查源租户该流程定义：同编码可能存在多条历史/空定义，取已发布且节点最完整的一条作为模板源
            List<Map<String, Object>> defs = jdbcTemplate.queryForList(
                    "SELECT d.*, (SELECT COUNT(*) FROM flow_node n WHERE n.definition_id = d.id) AS node_cnt " +
                    "FROM flow_definition d WHERE d.tenant_id = ? AND d.flow_code = ? AND d.del_flag = '0' " +
                    "AND d.is_publish = 1 ORDER BY node_cnt DESC, d.id DESC LIMIT 1",
                    srcTenant, flowCode);
            if (defs.isEmpty()) {
                log.warn("[模板复制] 源租户[{}]未找到流程编码[{}]，跳过", srcTenant, flowCode);
                continue;
            }
            Map<String, Object> def = defs.get(0);
            long oldDefId = ((Number) def.get("id")).longValue();

            // 查目标租户是否已有同 flowCode
            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM flow_definition WHERE tenant_id = ? AND flow_code = ? AND del_flag = '0'",
                    tgtTenant, flowCode);

            if (!existing.isEmpty()) {
                if ("skip".equalsIgnoreCase(conflictStrategy)) {
                    skipped++;
                    log.info("[模板复制] 目标租户[{}]已有流程[{}]，按策略跳过", tgtTenant, flowCode);
                    continue;
                }
                // overwrite：先删目标租户该编码的旧定义（含节点/跳转）
                long existId = ((Number) existing.get(0).get("id")).longValue();
                jdbcTemplate.update("DELETE FROM flow_skip WHERE definition_id = ? AND tenant_id = ?", existId, tgtTenant);
                jdbcTemplate.update("DELETE FROM flow_node WHERE definition_id = ? AND tenant_id = ?", existId, tgtTenant);
                jdbcTemplate.update("DELETE FROM flow_definition WHERE id = ? AND tenant_id = ?", existId, tgtTenant);
                log.info("[模板复制] 目标租户[{}]已有流程[{}]，已删除旧版本准备覆盖", tgtTenant, flowCode);
            }

            // 插入新定义
            long newDefId = IdWorker.getId();
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

            // 复制节点
            // node_type 是 tinyint(1)，JDBC 可能映射为 Boolean（2 会被读成 true=1，导致 end 节点被当成审批节点），
            // 必须用 node_type + 0 强制取数值
            List<Map<String, Object>> nodes = jdbcTemplate.queryForList(
                    "SELECT *, node_type + 0 AS node_type_num FROM flow_node WHERE definition_id = ? AND tenant_id = ?",
                    oldDefId, srcTenant);
            for (Map<String, Object> node : nodes) {
                jdbcTemplate.update(
                        "INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, " +
                        "node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, " +
                        "version, create_time, create_by, update_time, update_by, ext, del_flag, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(),
                        node.get("node_type_num"), newDefId, node.get("node_code"), node.get("node_name"),
                        node.get("permission_flag"), node.get("node_ratio"), node.get("coordinate"),
                        node.get("any_node_skip"), node.get("listener_type"), node.get("listener_path"),
                        node.get("form_custom"), node.get("form_path"), node.get("version"),
                        now, "system", now, "system", node.get("ext"), node.get("del_flag"), tgtTenant);
            }

            // 复制跳转
            // 跳转上的节点类型同为 tinyint(1)，同样用 + 0 取数值，避免 PASS/REJECT 判定错位
            List<Map<String, Object>> skips = jdbcTemplate.queryForList(
                    "SELECT *, now_node_type + 0 AS now_node_type_num, next_node_type + 0 AS next_node_type_num " +
                    "FROM flow_skip WHERE definition_id = ? AND tenant_id = ?", oldDefId, srcTenant);
            for (Map<String, Object> skip : skips) {
                jdbcTemplate.update(
                        "INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, " +
                        "next_node_type, skip_name, skip_type, skip_condition, coordinate, " +
                        "create_time, create_by, update_time, update_by, del_flag, tenant_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(),
                        newDefId, skip.get("now_node_code"), skip.get("now_node_type_num"),
                        skip.get("next_node_code"), skip.get("next_node_type_num"), skip.get("skip_name"),
                        skip.get("skip_type"), skip.get("skip_condition"), skip.get("coordinate"),
                        now, "system", now, "system", skip.get("del_flag"), tgtTenant);
            }
            copied++;
        }

        log.info("流程模板选择性复制完成: 目标租户[{}], 复制 {} 套, 跳过 {} 套", targetTenantId, copied, skipped);
        return copied;
    }

    /**
     * 补齐运行时表的租户ID（幂等，只填 NULL，不覆盖已有值）
     *
     * 引擎 1.8.9 写 flow_instance / flow_task / flow_user / flow_his_task 时不填 tenant_id，
     * 而查询会按租户过滤。漏任何一张都会导致"待办看不到"或"历史已办为空"。
     */
    private void syncRuntimeTenant(Long instanceId, Long tenantId) {
        if (instanceId == null || tenantId == null) {
            return;
        }
        String tenant = String.valueOf(tenantId);
        try {
            int instanceRows = jdbcTemplate.update(
                    "UPDATE flow_instance SET tenant_id = ? WHERE id = ? AND (tenant_id IS NULL OR tenant_id = '')",
                    tenant, instanceId);
            int taskRows = jdbcTemplate.update(
                    "UPDATE flow_task SET tenant_id = ? WHERE instance_id = ? AND (tenant_id IS NULL OR tenant_id = '')",
                    tenant, instanceId);
            int userRows = jdbcTemplate.update(
                    "UPDATE flow_user SET tenant_id = ? WHERE tenant_id IS NULL " +
                    "AND associated IN (SELECT id FROM flow_task WHERE instance_id = ?)",
                    tenant, instanceId);
            int hisRows = jdbcTemplate.update(
                    "UPDATE flow_his_task SET tenant_id = ? WHERE instance_id = ? AND (tenant_id IS NULL OR tenant_id = '')",
                    tenant, instanceId);
            log.info("[租户回填] instanceId={} tenant={} instance={} task={} user={} hisTask={}",
                    instanceId, tenant, instanceRows, taskRows, userRows, hisRows);
        } catch (Exception e) {
            // 回填失败不阻断审批主流程，但必须留痕，便于后续补偿
            log.error("[租户回填] 失败 instanceId={} tenant={}: {}", instanceId, tenant, e.getMessage());
        }
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
        ApprovalBill bill = null;
        if (task.getInstanceId() != null) {
            bill = approvalBillMapper.selectOne(new LambdaQueryWrapper<ApprovalBill>()
                    .eq(ApprovalBill::getProcessInstanceId, task.getInstanceId())
                    .last("limit 1"));
            item.put("billId", bill == null ? null : bill.getId());
            item.put("title", bill == null ? task.getBusinessId() : bill.getTitle());
            item.put("bizType", bill == null ? null : bill.getBizType());
            item.put("applyUserName", bill == null ? null : bill.getApplyUserName());
        }
        // 待办一律是"审批中"，但仍按统一模型输出，避免前端再出现裸数字
        WorkflowStatus status = WorkflowStatus.resolve(task.getFlowStatus(),
                bill == null ? null : bill.getApprovalStatus());
        item.put("statusCode", status.getCode());
        item.put("statusText", status.getText());
        item.put("statusTag", status.getTag());
        return item;
    }
}
