package com.netsight.modules.inspection.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.inspection.entity.InspectionItem;
import com.netsight.modules.inspection.entity.InspectionPlan;
import com.netsight.modules.inspection.entity.InspectionRecord;
import com.netsight.modules.inspection.entity.InspectionTask;
import com.netsight.modules.inspection.service.InspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 点检巡检接口
 * 点检项库 / 点检计划 / 点检任务 / 点检记录
 */
@RestController
@RequestMapping("/inspection")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    // ==================== 点检项库 ====================

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:item:list')")
    @GetMapping("/item/list")
    public R<PageResult<InspectionItem>> itemList(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String itemName) {
        return R.ok(inspectionService.pageItem(pageNum, pageSize, itemName));
    }

    /** 启用的点检项（执行任务时选择） */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/item/enabled")
    public R<List<InspectionItem>> itemEnabled() {
        return R.ok(inspectionService.listEnabledItems());
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:item:add')")
    @PostMapping("/item")
    public R<Void> itemAdd(@RequestBody InspectionItem item) {
        inspectionService.addItem(item);
        return R.ok("新增成功", null);
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:item:edit')")
    @PutMapping("/item")
    public R<Void> itemUpdate(@RequestBody InspectionItem item) {
        inspectionService.updateItem(item);
        return R.ok("修改成功", null);
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:item:remove')")
    @DeleteMapping("/item/{id}")
    public R<Void> itemRemove(@PathVariable Long id) {
        inspectionService.deleteItem(id);
        return R.ok("删除成功", null);
    }

    // ==================== 点检计划 ====================

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:plan:list')")
    @GetMapping("/plan/list")
    public R<PageResult<InspectionPlan>> planList(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String planName,
                                                  @RequestParam(required = false) Integer status) {
        return R.ok(inspectionService.pagePlan(pageNum, pageSize, planName, status));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:plan:add')")
    @PostMapping("/plan")
    public R<Void> planAdd(@RequestBody InspectionPlan plan) {
        inspectionService.addPlan(plan);
        return R.ok("新增成功", null);
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:plan:edit')")
    @PutMapping("/plan")
    public R<Void> planUpdate(@RequestBody InspectionPlan plan) {
        inspectionService.updatePlan(plan);
        return R.ok("修改成功", null);
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:plan:remove')")
    @DeleteMapping("/plan/{id}")
    public R<Void> planRemove(@PathVariable Long id) {
        inspectionService.deletePlan(id);
        return R.ok("删除成功", null);
    }

    /** 手动按计划生成指定日期任务（默认今天） */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('inspection:plan:edit')")
    @PostMapping("/plan/{id}/generate")
    public R<Integer> planGenerate(@PathVariable Long id,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(inspectionService.generateTasks(id, date));
    }

    // ==================== 点检任务 ====================

    /** 任务列表：管理员看全部（my=false），普通角色只看自己的任务 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/task/list")
    public R<PageResult<InspectionTask>> taskList(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String targetName,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) Boolean my) {
        return R.ok(inspectionService.pageTask(pageNum, pageSize, targetName, status, my));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/task/{id}")
    public R<Map<String, Object>> taskDetail(@PathVariable Long id) {
        return R.ok(inspectionService.taskDetail(id));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/task/{id}/start")
    public R<Void> taskStart(@PathVariable Long id) {
        inspectionService.startTask(id);
        return R.ok("已开始执行", null);
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/task/submit")
    public R<Map<String, Object>> taskSubmit(@RequestBody Map<String, Object> body) {
        return R.ok(inspectionService.submitTask(body));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/task/stats")
    public R<Map<String, Object>> taskStats() {
        return R.ok(inspectionService.taskStats());
    }

    // ==================== 点检记录 ====================

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/record/list")
    public R<PageResult<InspectionRecord>> recordList(@RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "10") long pageSize,
                                                      @RequestParam(required = false) Long taskId,
                                                      @RequestParam(required = false) Integer result) {
        return R.ok(inspectionService.pageRecord(pageNum, pageSize, taskId, result));
    }
}
