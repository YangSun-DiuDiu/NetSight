package com.netsight.modules.spare.controller;

import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.spare.entity.SpareOutRequest;
import com.netsight.modules.spare.service.SpareOutService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 备件出库/入库申请控制器（流程审批入口）
 *
 * - POST /spare/out/request   发起领料出库申请（H5 工单领料，强制关联工单）
 * - POST /spare/in/request    发起入库申请（发起人=设备管理员）
 * - GET  /spare/out/mine      我的申请列表
 * - GET  /spare/out/order/{orderId}  按工单查申请列表（H5 工单详情）
 * - POST /spare/out/resubmit  驳回后重新提交
 */
@Slf4j
@RestController
@RequestMapping("/spare/out")
@RequiredArgsConstructor
public class SpareOutController {

    private final SpareOutService spareOutService;

    /** 发起领料出库申请（H5/PC，强制关联工单） */
    @PostMapping("/request")
    @Log(module = "备品备件", action = "发起出库申请")
    public R<SpareOutRequest> requestOut(@RequestBody OutRequest req) {
        return R.ok("出库申请已提交，等待审批", spareOutService.requestOut(req.getOrderId(), req.getItems(), req.getRemark()));
    }

    /** 我的申请列表（出库+入库） */
    @GetMapping("/mine")
    public R<List<SpareOutRequest>> myRequests() {
        return R.ok(spareOutService.myRequests());
    }

    /** 按工单查申请列表（H5 工单详情展示） */
    @GetMapping("/order/{orderId}")
    public R<List<SpareOutRequest>> listByOrder(@PathVariable Long orderId) {
        return R.ok(spareOutService.listByOrder(orderId));
    }

    /** 驳回后重新提交 */
    @PostMapping("/resubmit")
    @Log(module = "备品备件", action = "重新提交申请")
    public R<SpareOutRequest> resubmit(@RequestBody ResubmitRequest req) {
        return R.ok("重新提交成功", spareOutService.resubmit(req.getBizId()));
    }

    // ==================== 请求体 ====================

    @Data
    public static class OutRequest {
        /** 关联工单ID（强制） */
        @NotNull(message = "工单ID不能为空")
        private Long orderId;
        /** 备件明细 [{partId, quantity}] */
        @NotEmpty(message = "领料明细不能为空")
        private List<Map<String, Object>> items;
        private String remark;
    }

    @Data
    public static class ResubmitRequest {
        @NotBlank(message = "申请单号不能为空")
        private String bizId;
    }
}
