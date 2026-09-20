package com.netsight.modules.workorder.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.workorder.entity.WorkOrder;
import com.netsight.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.netsight.framework.aspectj.Log;

/**
 * 故障工单控制器（运维工单 - 工单管理）
 */
@Slf4j
@RestController
@RequestMapping("/workorder/order")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    /**
     * 工单分页查询
     * GET /workorder/order/list?pageNum=1&pageSize=10&orderNo=&status=&deviceName=&faultType=
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:list')")
    @GetMapping("/list")
    public R<PageResult<WorkOrder>> list(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) String orderNo,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String deviceName,
                                         @RequestParam(required = false) String faultType) {
        return R.ok(workOrderService.pageOrder(pageNum, pageSize, orderNo, status, deviceName, faultType));
    }

    /**
     * 工单统计（顶部卡片）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:list')")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(workOrderService.stats());
    }

    /**
     * 工单详情（含处理记录 + 备件关联）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:list')")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        return R.ok(workOrderService.detail(id));
    }

    /**
     * 手动新增工单
     * Body: {"deviceName":"...","deviceIp":"...","deviceType":"network","deviceLocation":"...","faultType":"manual","severity":"info","description":"..."}
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:add')")
    @PostMapping
    @Log(module="工单管理", action="手动建单")
    public R<Long> add(@RequestBody WorkOrder order) {
        return R.ok("联系单已生成", workOrderService.addOrder(order));
    }

    /**
     * 修改工单（仅待处理）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:edit')")
    @PutMapping
    @Log(module="工单管理", action="修改工单")
    public R<Void> update(@RequestBody WorkOrder order) {
        workOrderService.updateOrder(order);
        return R.ok();
    }

    /**
     * 删除工单
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:remove')")
    @DeleteMapping("/{id}")
    @Log(module="工单管理", action="删除工单")
    public R<Void> remove(@PathVariable Long id) {
        workOrderService.removeOrder(id);
        return R.ok();
    }

    /**
     * 报修派单：选择维修人员，自动通知（短信+公众号）
     * Body: {"id":1,"repairerId":2,"remark":"请尽快上门"}
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:dispatch')")
    @PostMapping("/dispatch")
    @Log(module="工单管理", action="报修派单")
    public R<Void> dispatch(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(String.valueOf(body.get("id")));
        Long repairerId = Long.valueOf(String.valueOf(body.get("repairerId")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        workOrderService.dispatch(id, repairerId, remark);
        return R.ok("已报修派单并通知维修人员", null);
    }

    /**
     * 开始维修（已派单 → 维修中）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:complete')")
    @PostMapping("/repairStart")
    @Log(module="工单管理", action="开始维修")
    public R<Void> repairStart(@RequestBody Map<String, Object> body) {
        workOrderService.repairStart(Long.valueOf(String.valueOf(body.get("id"))));
        return R.ok("已开始维修", null);
    }

    /**
     * 完工（回填维修结果）
     * Body: {"id":1,"repairResult":"更换光模块后恢复"}
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:complete')")
    @PostMapping("/complete")
    @Log(module="工单管理", action="维修完工")
    public R<Void> complete(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(String.valueOf(body.get("id")));
        String result = body.get("repairResult") == null ? null : String.valueOf(body.get("repairResult"));
        workOrderService.complete(id, result);
        return R.ok("工单已完成", null);
    }

    /**
     * 关闭工单（管理员自行处理）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:order:complete')")
    @PostMapping("/close")
    @Log(module="工单管理", action="关闭工单")
    public R<Void> close(@RequestBody Map<String, Object> body) {
        Long id = Long.valueOf(String.valueOf(body.get("id")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        workOrderService.close(id, remark);
        return R.ok("工单已关闭", null);
    }
}
