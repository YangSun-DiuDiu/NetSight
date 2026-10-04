package com.netsight.modules.workorder.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.spare.entity.SparePart;
import com.netsight.modules.spare.mapper.SparePartMapper;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动端工单控制器（维修人员 H5 专用）
 * 维修人员通过手机号登录后，查看派给自己的工单、开始维修、上传照片、领用备件、完工提交
 */
@Slf4j
@RestController
@RequestMapping("/m/order")
@RequiredArgsConstructor
public class MobileWorkOrderController {

    private final WorkOrderMapper orderMapper;
    private final WorkOrderRecordMapper recordMapper;
    private final WorkOrderPartMapper orderPartMapper;
    private final RepairerMapper repairerMapper;
    private final SparePartMapper sparePartMapper;

    /**
     * 当前登录用户对应的维修人员（按手机号匹配）
     */
    private Repairer currentRepairer() {
        String phone = SecurityUtils.getLoginUser().getPhone();
        if (!StringUtils.hasText(phone)) {
            throw new ServiceException(500, "当前账号未绑定维修人员手机号");
        }
        Repairer repairer = repairerMapper.selectOne(new LambdaQueryWrapper<Repairer>()
                .eq(Repairer::getPhone, phone)
                .eq(Repairer::getTenantId, SecurityUtils.getTenantId())
                .last("LIMIT 1"));
        if (repairer == null) {
            throw new ServiceException(500, "当前账号未关联维修人员档案，请联系管理员");
        }
        return repairer;
    }

    /**
     * 我的工单列表（按状态筛选）
     * GET /m/order/my-list?status=1&pageNum=1&pageSize=10
     */
    @GetMapping("/my-list")
    public R<PageResult<WorkOrder>> myList(@RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        Repairer repairer = currentRepairer();
        Page<WorkOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getRepairerId, repairer.getId())
                .eq(status != null, WorkOrder::getStatus, status)
                .orderByDesc(WorkOrder::getId);
        Page<WorkOrder> result = orderMapper.selectPage(page, wrapper);

        // 填充状态文本
        Map<Integer, String> statusText = Map.of(
                0, "待处理", 1, "已派单", 2, "维修中", 3, "已完成", 4, "已关闭", 5, "已自动恢复");
        result.getRecords().forEach(o ->
                o.setStatusText(statusText.getOrDefault(o.getStatus(), String.valueOf(o.getStatus()))));

        return R.ok(PageResult.of(result.getTotal(), result.getRecords()));
    }

    /**
     * 我的工单统计（顶部Tab数字）
     */
    @GetMapping("/my-stats")
    public R<Map<String, Object>> myStats() {
        Repairer repairer = currentRepairer();
        Map<String, Object> stats = new HashMap<>();
        stats.put("dispatched", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getRepairerId, repairer.getId()).eq(WorkOrder::getStatus, 1)));
        stats.put("repairing", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getRepairerId, repairer.getId()).eq(WorkOrder::getStatus, 2)));
        stats.put("completed", orderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getRepairerId, repairer.getId()).eq(WorkOrder::getStatus, 3)));
        return R.ok(stats);
    }

    /**
     * 工单详情（含处理记录 + 备件）
     */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        Repairer repairer = currentRepairer();
        WorkOrder order = orderMapper.selectById(id);
        if (order == null || !order.getRepairerId().equals(repairer.getId())) {
            throw new ServiceException(500, "工单不存在或不属于当前维修人员");
        }
        Map<String, Object> result = new HashMap<>();
        Map<Integer, String> statusText = Map.of(
                0, "待处理", 1, "已派单", 2, "维修中", 3, "已完成", 4, "已关闭", 5, "已自动恢复");
        order.setStatusText(statusText.getOrDefault(order.getStatus(), String.valueOf(order.getStatus())));
        result.put("order", order);
        result.put("records", recordMapper.selectList(new LambdaQueryWrapper<WorkOrderRecord>()
                .eq(WorkOrderRecord::getOrderId, id).orderByAsc(WorkOrderRecord::getId)));
        result.put("parts", orderPartMapper.selectList(new LambdaQueryWrapper<WorkOrderPart>()
                .eq(WorkOrderPart::getOrderId, id)));
        return R.ok(result);
    }

    /**
     * 开始维修（已派单 → 维修中）
     */
    @PostMapping("/{id}/start")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> start(@PathVariable Long id) {
        Repairer repairer = currentRepairer();
        WorkOrder order = orderMapper.selectById(id);
        if (order == null || !order.getRepairerId().equals(repairer.getId())) {
            throw new ServiceException(500, "工单不存在或不属于当前维修人员");
        }
        if (order.getStatus() != 1) {
            throw new ServiceException(500, "仅已派单状态可开始维修");
        }
        order.setStatus(2);
        order.setRepairStartTime(LocalDateTime.now());
        orderMapper.updateById(order);
        addRecord(order, "repair_start", repairer.getName(), "开始维修");
        return R.ok("已开始维修", null);
    }

    /**
     * 完工提交（维修中 → 已完成）
     * Body: {"repairResult":"...","photos":["url1","url2"],"parts":[{"partId":1,"quantity":1}]}
     */
    @PostMapping("/{id}/complete")
    @Transactional(rollbackFor = Exception.class)
    public R<Void> complete(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Repairer repairer = currentRepairer();
        WorkOrder order = orderMapper.selectById(id);
        if (order == null || !order.getRepairerId().equals(repairer.getId())) {
            throw new ServiceException(500, "工单不存在或不属于当前维修人员");
        }
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            throw new ServiceException(500, "仅已派单/维修中状态可完工");
        }

        String repairResult = body.get("repairResult") == null ? null : String.valueOf(body.get("repairResult"));
        if (!StringUtils.hasText(repairResult)) {
            throw new ServiceException(500, "维修结果不能为空");
        }

        // 照片校验
        Object photosObj = body.get("photos");
        if (photosObj == null || ((List<?>) photosObj).isEmpty()) {
            throw new ServiceException(500, "请至少上传一张维修照片");
        }
        String photosJson = toJsonArray(photosObj);

        // 更新工单
        order.setStatus(3);
        order.setRepairResult(repairResult);
        order.setRepairPhotos(photosJson);
        order.setRepairEndTime(LocalDateTime.now());
        if (order.getRepairStartTime() == null) {
            order.setRepairStartTime(LocalDateTime.now());
        }
        orderMapper.updateById(order);
        addRecord(order, "complete", repairer.getName(), "完工：" + repairResult);

        // 领用备件
        Object partsObj = body.get("parts");
        if (partsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) partsObj;
            for (Map<String, Object> p : parts) {
                Long partId = Long.valueOf(String.valueOf(p.get("partId")));
                Integer qty = Integer.valueOf(String.valueOf(p.getOrDefault("quantity", 1)));
                SparePart part = sparePartMapper.selectById(partId);
                if (part == null) continue;
                // 扣减库存
                if (part.getQuantity() < qty) {
                    throw new ServiceException(500, "备件[" + part.getPartType() + " " + part.getBrand() + " " + part.getModel() + "]库存不足");
                }
                part.setQuantity(part.getQuantity() - qty);
                sparePartMapper.updateById(part);
                // 关联工单
                WorkOrderPart op = new WorkOrderPart();
                op.setTenantId(order.getTenantId());
                op.setOrderId(order.getId());
                op.setPartId(partId);
                op.setPartNo(part.getPartNo());
                op.setPartName(part.getPartType() + " " + part.getBrand() + " " + part.getModel());
                op.setQuantity(qty);
                op.setUnit(part.getUnit());
                orderPartMapper.insert(op);
            }
        }

        return R.ok("工单已完成", null);
    }

    /**
     * 可领用备件列表（当前租户有库存的）
     */
    @GetMapping("/parts")
    public R<List<SparePart>> parts() {
        List<SparePart> list = sparePartMapper.selectList(new LambdaQueryWrapper<SparePart>()
                .gt(SparePart::getQuantity, 0)
                .orderByAsc(SparePart::getPartType));
        return R.ok(list);
    }

    private void addRecord(WorkOrder order, String action, String operator, String content) {
        WorkOrderRecord record = new WorkOrderRecord();
        record.setTenantId(order.getTenantId());
        record.setOrderId(order.getId());
        record.setAction(action);
        record.setOperator(operator);
        record.setContent(content);
        recordMapper.insert(record);
    }

    private String toJsonArray(Object obj) {
        if (!(obj instanceof List)) return "[]";
        List<?> list = (List<?>) obj;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(String.valueOf(list.get(i)).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
