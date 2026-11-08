package com.netsight.modules.workorder.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.system.dto.SysUserOption;
import com.netsight.modules.workorder.dto.RepairerAccountRequest;
import com.netsight.modules.workorder.dto.RepairerSaveResult;
import com.netsight.modules.workorder.entity.Repairer;
import com.netsight.modules.workorder.service.RepairerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 维修人员库控制器（运维工单 - 维修人员）。
 */
@Slf4j
@RestController
@RequestMapping("/workorder/repairer")
@RequiredArgsConstructor
public class RepairerController {

    private final RepairerService repairerService;

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/list")
    public R<PageResult<Repairer>> list(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String name,
                                        @RequestParam(required = false) Integer status) {
        return R.ok(repairerService.pageRepairer(pageNum, pageSize, keyword, name, status));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:list')")
    @GetMapping("/options")
    public R<List<Repairer>> options(@RequestParam(required = false) Long workOrderId) {
        return R.ok(repairerService.listOptions(workOrderId));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:add')")
    @GetMapping("/user-options")
    public R<PageResult<SysUserOption>> userOptions(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize,
                                                   @RequestParam(required = false) String keyword) {
        return R.ok(repairerService.pageUserOptions(pageNum, pageSize, keyword));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:add')")
    @PostMapping
    @Log(module = "维修人员", action = "新增维修人员")
    public R<RepairerSaveResult> add(@RequestBody Repairer repairer) {
        return R.ok("维修人员已添加", repairerService.addRepairer(repairer));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:edit')")
    @PostMapping("/{id}/account")
    @Log(module = "维修人员", action = "补建账号")
    public R<RepairerSaveResult> bindAccount(@PathVariable Long id,
                                             @RequestBody RepairerAccountRequest request) {
        return R.ok("账号已绑定",
                repairerService.bindAccount(id, request.getRepairerType()));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:edit')")
    @PutMapping
    @Log(module = "维修人员", action = "修改维修人员")
    public R<Void> update(@RequestBody Repairer repairer) {
        repairerService.updateRepairer(repairer);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('workorder:repairer:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "维修人员", action = "删除维修人员")
    public R<Void> remove(@PathVariable Long id) {
        repairerService.removeRepairer(id);
        return R.ok();
    }
}
