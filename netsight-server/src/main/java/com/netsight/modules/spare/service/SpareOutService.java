package com.netsight.modules.spare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.spare.entity.SpareOutRequest;
import com.netsight.modules.spare.mapper.SpareOutRequestMapper;
import com.netsight.modules.workflow.entity.ApprovalBill;
import com.netsight.modules.workflow.service.WorkflowService;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 备件出库/入库申请服务（方案B：提交即扣/加库存 + 审批存档）
 *
 * 出库（领料）：H5 维修人员/设备管理员从工单发起领料 → 立即扣库存（spare_part、
 * spare_part_record、work_order_part 工单绑定，复用 SparePartService.stock）→
 * 登记 spare_out_request → 启动 warm-flow 出库审批（spare_out_stock）。
 * 入库：设备管理员发起入库 → 立即加库存 → 启动入库审批（spare_in_stock）。
 * 审批结果仅存档：approved/rejected 通过事件回调同步本表；驳回后可同 bizId 重新提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpareOutService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELED = "canceled";

    private final SpareOutRequestMapper outRequestMapper;
    private final SparePartService sparePartService;
    private final WorkOrderMapper workOrderMapper;
    private final WorkflowService workflowService;

    /**
     * 发起出库申请（领料）
     *
     * @param orderId 关联工单ID（H5 领料强制带当前工单）
     * @param items   备件明细 [{partId, quantity}]
     * @param remark  备注
     * @return 出库申请单
     */
    @Transactional(rollbackFor = Exception.class)
    public SpareOutRequest requestOut(Long orderId, List<Map<String, Object>> items, String remark) {
        if (orderId == null) {
            throw new ServiceException(400, "领料申请必须关联工单");
        }
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException(400, "工单不存在");
        }
        if (!SecurityUtils.isSuperAdmin()
                && (order.getTenantId() == null || !order.getTenantId().equals(SecurityUtils.getTenantId()))) {
            throw new ServiceException(403, "无权操作其他租户的工单");
        }
        if (items == null || items.isEmpty()) {
            throw new ServiceException(400, "领料明细不能为空");
        }

        String bizId = genNo("OUT");
        // 1. 立即扣库存 + 流水 + 工单备件绑定（方案B）
        for (Map<String, Object> item : items) {
            Long partId = Long.valueOf(String.valueOf(item.get("partId")));
            Integer quantity = Integer.valueOf(String.valueOf(item.get("quantity")));
            if (quantity == null || quantity <= 0) {
                throw new ServiceException(400, "领料数量必须大于0");
            }
            sparePartService.stock(partId, "out", quantity, orderId, order.getOrderNo(),
                    "领料出库申请 " + bizId + (StringUtils.hasText(remark) ? "：" + remark : ""));
        }

        // 2. 登记出库申请单
        SpareOutRequest request = buildRequest(order, bizId, items, remark);
        outRequestMapper.insert(request);

        // 3. 启动审批（spare_out_stock 模板，审批人=租户管理员）
        String title = "备件出库申请 #" + order.getOrderNo();
        ApprovalBill bill = workflowService.start("spare_out_stock", "out_stock", bizId, title);
        request.setBillId(bill.getId());
        request.setProcessInstanceId(bill.getProcessInstanceId());
        outRequestMapper.updateById(request);

        log.info("出库申请发起 bizId={} orderNo={} billId={}", bizId, order.getOrderNo(), bill.getId());
        return request;
    }

    /**
     * 发起入库申请（发起人须为设备管理员 device_admin 或超管）
     *
     * @param items  备件明细 [{partId, quantity}]
     * @param remark 备注
     * @return 入库申请单
     */
    @Transactional(rollbackFor = Exception.class)
    public SpareOutRequest requestIn(List<Map<String, Object>> items, String remark) {
        // 入库审批发起人为设备管理员（用户确认方案）
        if (!SecurityUtils.isSuperAdmin() && !hasRole("device_admin")) {
            throw new ServiceException(403, "仅设备管理员可发起入库申请");
        }
        if (items == null || items.isEmpty()) {
            throw new ServiceException(400, "入库明细不能为空");
        }
        String bizId = genNo("IN");
        // 1. 立即加库存 + 流水（方案B）
        for (Map<String, Object> item : items) {
            Long partId = Long.valueOf(String.valueOf(item.get("partId")));
            Integer quantity = Integer.valueOf(String.valueOf(item.get("quantity")));
            if (quantity == null || quantity <= 0) {
                throw new ServiceException(400, "入库数量必须大于0");
            }
            sparePartService.stock(partId, "in", quantity, null, null,
                    "入库申请 " + bizId + (StringUtils.hasText(remark) ? "：" + remark : ""));
        }
        // 2. 登记申请单（无工单关联）
        SpareOutRequest request = buildRequest(null, bizId, items, remark);
        outRequestMapper.insert(request);
        // 3. 启动入库审批
        ApprovalBill bill = workflowService.start("spare_in_stock", "in_stock", bizId, "备件入库申请 #" + bizId);
        request.setBillId(bill.getId());
        request.setProcessInstanceId(bill.getProcessInstanceId());
        outRequestMapper.updateById(request);
        log.info("入库申请发起 bizId={} billId={}", bizId, bill.getId());
        return request;
    }

    /**
     * 我的申请列表（出库+入库，按发起人，倒序）
     */
    public List<SpareOutRequest> myRequests() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return List.of();
        }
        return outRequestMapper.selectList(new LambdaQueryWrapper<SpareOutRequest>()
                .eq(SpareOutRequest::getApplyUserId, userId)
                .orderByDesc(SpareOutRequest::getId));
    }

    /**
     * 按工单查询申请列表（H5 工单详情页展示）
     */
    public List<SpareOutRequest> listByOrder(Long orderId) {
        return outRequestMapper.selectList(new LambdaQueryWrapper<SpareOutRequest>()
                .eq(SpareOutRequest::getOrderId, orderId)
                .orderByDesc(SpareOutRequest::getId));
    }

    /**
     * 驳回后重新提交（同 bizId 重新发起流程，新审批单新实例，历史审批链保留）
     */
    @Transactional(rollbackFor = Exception.class)
    public SpareOutRequest resubmit(String bizId) {
        SpareOutRequest request = getOwnRequestByBizId(bizId);
        if (!STATUS_REJECTED.equals(request.getApprovalStatus())) {
            throw new ServiceException(400, "仅驳回状态的申请可重新提交，当前状态：" + request.getApprovalStatus());
        }
        boolean isIn = request.getBizId() != null && request.getBizId().startsWith("IN-");
        ApprovalBill bill = workflowService.start(
                isIn ? "spare_in_stock" : "spare_out_stock",
                isIn ? "in_stock" : "out_stock",
                request.getBizId(),
                (isIn ? "备件入库申请 #" : "备件出库申请 #") + request.getBizId());
        request.setApprovalStatus(STATUS_PROCESSING);
        request.setBillId(bill.getId());
        request.setProcessInstanceId(bill.getProcessInstanceId());
        request.setRemark("重新提交：" + LocalDateTime.now().format(NO_FMT));
        outRequestMapper.updateById(request);
        log.info("出库/入库申请重新提交 bizId={} billId={}", bizId, bill.getId());
        return request;
    }

    /**
     * 审批结果回调（workflow approve 事件）：同步申请单状态（审批存档）
     */
    public void onApproval(String bizType, String bizId, boolean pass, String comment) {
        if (!"out_stock".equals(bizType) && !"in_stock".equals(bizType)) {
            return;
        }
        SpareOutRequest request = outRequestMapper.selectOne(new LambdaQueryWrapper<SpareOutRequest>()
                .eq(SpareOutRequest::getBizId, bizId).last("LIMIT 1"));
        if (request == null) {
            log.warn("审批回调未找到申请单 bizId={}", bizId);
            return;
        }
        request.setApprovalStatus(pass ? STATUS_APPROVED : STATUS_REJECTED);
        if (StringUtils.hasText(comment)) {
            request.setRemark(comment);
        }
        outRequestMapper.updateById(request);
        log.info("申请单审批存档 bizId={} status={}", bizId, request.getApprovalStatus());
    }

    // ==================== 内部 ====================

    private SpareOutRequest buildRequest(WorkOrder order, String bizId,
                                         List<Map<String, Object>> items, String remark) {
        SpareOutRequest request = new SpareOutRequest();
        request.setTenantId(SecurityUtils.getTenantId());
        request.setBizId(bizId);
        request.setApprovalStatus(STATUS_PROCESSING);
        request.setApplyUserId(SecurityUtils.getUserId());
        request.setApplyUserName(SecurityUtils.getLoginUser().getRealName());
        if (order != null) {
            request.setOrderId(order.getId());
            request.setOrderNo(order.getOrderNo());
        }
        if (StringUtils.hasText(remark)) {
            request.setRemark(remark);
        }
        try {
            request.setItems(OBJECT_MAPPER.writeValueAsString(items));
        } catch (Exception e) {
            log.warn("出库明细 JSON 序列化失败: {}", e.getMessage());
            request.setItems("[]");
        }
        return request;
    }

    private SpareOutRequest getOwnRequestByBizId(String bizId) {
        if (!StringUtils.hasText(bizId)) {
            throw new ServiceException(400, "申请单号不能为空");
        }
        SpareOutRequest request = outRequestMapper.selectOne(new LambdaQueryWrapper<SpareOutRequest>()
                .eq(SpareOutRequest::getBizId, bizId).last("LIMIT 1"));
        if (request == null || !request.getApplyUserId().equals(SecurityUtils.getUserId())) {
            throw new ServiceException(404, "申请单不存在或不属于当前用户");
        }
        return request;
    }

    private boolean hasRole(String role) {
        if (SecurityUtils.getLoginUser() == null || SecurityUtils.getLoginUser().getRoles() == null) {
            return false;
        }
        return SecurityUtils.getLoginUser().getRoles().contains(role);
    }

    private String genNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }
}
