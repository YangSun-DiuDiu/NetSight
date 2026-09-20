package com.netsight.modules.spare.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.spare.entity.SparePart;
import com.netsight.modules.spare.service.SparePartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.netsight.framework.aspectj.Log;

/**
 * 备品备件控制器（备品备件 - 备件管理）
 */
@Slf4j
@RestController
@RequestMapping("/spare/part")
@RequiredArgsConstructor
public class SparePartController {

    private final SparePartService sparePartService;

    /**
     * 备件分页查询
     * GET /spare/part/list?pageNum=1&pageSize=10&partType=&brand=&status=&keyword=
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:list')")
    @GetMapping("/list")
    public R<PageResult<SparePart>> list(@RequestParam(defaultValue = "1") long pageNum,
                                         @RequestParam(defaultValue = "10") long pageSize,
                                         @RequestParam(required = false) String partType,
                                         @RequestParam(required = false) String brand,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String keyword) {
        return R.ok(sparePartService.pagePart(pageNum, pageSize, partType, brand, status, keyword));
    }

    /**
     * 备件统计（顶部卡片）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:list')")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(sparePartService.stats());
    }

    /**
     * 备件类型列表（筛选下拉）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:list')")
    @GetMapping("/types")
    public R<List<SparePart>> types() {
        return R.ok(sparePartService.listTypes());
    }

    /**
     * 可用备件选项（工单领用弹窗）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:list')")
    @GetMapping("/available")
    public R<List<SparePart>> available() {
        return R.ok(sparePartService.listAvailable());
    }

    /**
     * 新增备件
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:add')")
    @PostMapping
    @Log(module="备品备件", action="新增备件")
    public R<Long> add(@RequestBody SparePart part) {
        return R.ok("备件已登记", sparePartService.addPart(part));
    }

    /**
     * 修改备件（库存走出入库，不在此改）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:edit')")
    @PutMapping
    @Log(module="备品备件", action="修改备件")
    public R<Void> update(@RequestBody SparePart part) {
        sparePartService.updatePart(part);
        return R.ok();
    }

    /**
     * 删除备件
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:remove')")
    @DeleteMapping("/{id}")
    @Log(module="备品备件", action="删除备件")
    public R<Void> remove(@PathVariable Long id) {
        sparePartService.removePart(id);
        return R.ok();
    }

    /**
     * 出入库操作
     * Body: {"partId":1,"recordType":"out","quantity":2,"orderId":3,"orderNo":"WO...","remark":"更换光模块"}
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:part:stock')")
    @PostMapping("/stock")
    @Log(module="备品备件", action="备件出入库")
    public R<Void> stock(@RequestBody Map<String, Object> body) {
        Long partId = Long.valueOf(String.valueOf(body.get("partId")));
        String recordType = String.valueOf(body.get("recordType"));
        Integer quantity = Integer.valueOf(String.valueOf(body.get("quantity")));
        Long orderId = body.get("orderId") == null ? null : Long.valueOf(String.valueOf(body.get("orderId")));
        String orderNo = body.get("orderNo") == null ? null : String.valueOf(body.get("orderNo"));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        sparePartService.stock(partId, recordType, quantity, orderId, orderNo, remark);
        return R.ok("出入库操作完成", null);
    }
}
