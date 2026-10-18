package com.netsight.modules.workflow.controller;

import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.service.WorkflowService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.entity.Definition;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 审批工作流接口
 *
 * 提供流程定义查询、发起、审批、撤销、待办、我发起的、详情/历史能力，
 * 前端页面：流程设计器（/warm-flow-ui/**，引擎自带）、流程定义、我的发起、统一待办审批。
 */
@RestController
@RequestMapping("/workflow")
@Slf4j
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    /** 流程定义列表 */
    @GetMapping("/definitions")
    public R<List<Definition>> definitions() {
        return R.ok(workflowService.listDefinitions());
    }

    /** 发起流程 */
    @PostMapping("/start")
    @Log(module = "流程审批", action = "发起审批")
    public R<ApprovalBill> start(@RequestBody StartRequest request) {
        return R.ok(workflowService.start(
                request.getFlowCode(), request.getBizType(), request.getBizId(), request.getTitle()));
    }

    /** 审批（通过/驳回） */
    @PostMapping("/approve")
    @Log(module = "流程审批", action = "审批操作")
    public R<ApprovalBill> approve(@RequestBody ApproveRequest request) {
        return R.ok(workflowService.approve(request.getBillId(), request.isPass(), request.getComment()));
    }

    /** 撤销流程 */
    @PostMapping("/revoke")
    @Log(module = "流程审批", action = "撤销流程")
    public R<ApprovalBill> revoke(@RequestBody RevokeRequest request) {
        return R.ok(workflowService.revoke(request.getBillId()));
    }

    /** 我的待办 */
    @GetMapping("/mine/todo")
    public R<List<Map<String, Object>>> myTodo() {
        return R.ok(workflowService.myTodo());
    }

    /** 我发起的 */
    @GetMapping("/mine/started")
    public R<List<ApprovalBill>> myStarted() {
        return R.ok(workflowService.myStarted());
    }

    /** 我审批过的（历史已办） */
    @GetMapping("/mine/done")
    public R<java.util.Map<String, Object>> myDone(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(workflowService.myDone(pageNum, pageSize));
    }

    /** 审批单详情 + 流程历史 */
    @GetMapping("/bill/{billId}")
    public R<Map<String, Object>> billDetail(@PathVariable Long billId) {
        return R.ok(workflowService.billDetail(billId));
    }

    /**
     * 流程模板租户复制（方案 B 手工补复制，超管权限）
     * 场景：存量租户在方案 B 上线前已创建，无预置流程模板；或租户模板被误删需重置。
     * 前端：流程定义页「复制模板到租户」功能。
     */
    @PostMapping("/template/copy")
    @PreAuthorize("hasRole('super_admin')")
    @Log(module = "流程审批", action = "复制流程模板")
    public R<Integer> copyTemplates(@RequestBody CopyTemplateRequest request) {
        int copied = workflowService.copyFlowTemplatesToTenant(
                request.getSourceTenantId(), request.getTargetTenantId());
        return R.ok("已复制 " + copied + " 套流程模板到目标租户", copied);
    }

    // ==================== 请求体 ====================

    @Data
    public static class CopyTemplateRequest {
        /** 源租户ID（预置模板所在租户，默认1） */
        @NotNull(message = "源租户ID不能为空")
        private Long sourceTenantId;
        /** 目标租户ID */
        @NotNull(message = "目标租户ID不能为空")
        private Long targetTenantId;
    }

    @Data
    public static class StartRequest {
        /** 流程编码 */
        @NotBlank(message = "流程编码不能为空")
        private String flowCode;
        /** 业务类型：out_stock / in_stock / workorder_accept 等 */
        @NotBlank(message = "业务类型不能为空")
        private String bizType;
        /** 业务单号 */
        @NotBlank(message = "业务单号不能为空")
        private String bizId;
        /** 审批标题 */
        private String title;
    }

    @Data
    public static class ApproveRequest {
        /** 审批单ID */
        @NotNull(message = "审批单ID不能为空")
        private Long billId;
        /** 是否通过 */
        private boolean pass;
        /** 审批意见 */
        private String comment;
    }

    @Data
    public static class RevokeRequest {
        @NotNull(message = "审批单ID不能为空")
        private Long billId;
    }
}
