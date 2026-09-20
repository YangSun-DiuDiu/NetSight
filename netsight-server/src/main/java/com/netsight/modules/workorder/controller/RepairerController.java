package com.netsight.modules.workorder.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.netsight.framework.aspectj.Log;

/**
 * 维修人员库控制器（运维工单 - 维修人员）
 */
@Slf4j
@RestController
@RequestMapping("/workorder/repairer")
@RequiredArgsConstructor
public class RepairerController {

    private final RepairerService repairerService;

    /**
     * 分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/list")
    public R<PageResult<Repairer>> list(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) Integer status) {
        return R.ok(repairerService.pageRepairer(pageNum, pageSize, name, status));
    }

    /**
     * 在岗维修人员选项（派单弹窗下拉）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/options")
    public R<List<Repairer>> options() {
        return R.ok(repairerService.listOptions());
    }

    /**
     * 新增维修人员
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:add')")
    @PostMapping
    @Log(module="维修人员", action="新增维修人员")
    public R<Long> add(@RequestBody Repairer repairer) {
        return R.ok("维修人员已添加", repairerService.addRepairer(repairer));
    }

    /**
     * 修改维修人员
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:edit')")
    @PutMapping
    @Log(module="维修人员", action="修改维修人员")
    public R<Void> update(@RequestBody Repairer repairer) {
        repairerService.updateRepairer(repairer);
        return R.ok();
    }

    /**
     * 删除维修人员
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:remove')")
    @DeleteMapping("/{id}")
    @Log(module="维修人员", action="删除维修人员")
    public R<Void> remove(@PathVariable Long id) {
        repairerService.removeRepairer(id);
        return R.ok();
    }
}
