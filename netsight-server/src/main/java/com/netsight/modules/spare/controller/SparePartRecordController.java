package com.netsight.modules.spare.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.spare.entity.SparePartRecord;
import com.netsight.modules.spare.service.SparePartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 备件出入库记录控制器（备品备件 - 出入库记录）
 */
@Slf4j
@RestController
@RequestMapping("/spare/record")
@RequiredArgsConstructor
public class SparePartRecordController {

    private final SparePartService sparePartService;

    /**
     * 出入库记录分页
     * GET /spare/record/list?pageNum=1&pageSize=10&partNo=&recordType=&orderNo=
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('spare:record:list')")
    @GetMapping("/list")
    public R<PageResult<SparePartRecord>> list(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String partNo,
                                               @RequestParam(required = false) String recordType,
                                               @RequestParam(required = false) String orderNo) {
        return R.ok(sparePartService.pageRecord(pageNum, pageSize, partNo, recordType, orderNo));
    }
}
