package com.netsight.modules.spare.controller;

import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.spare.entity.SpareOutRequest;
import com.netsight.modules.spare.service.SpareOutService;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 备件入库申请控制器（发起人=设备管理员）
 *
 * - POST /spare/in/request  发起入库申请（提交即加库存 + 启动入库审批存档）
 */
@Slf4j
@RestController
@RequestMapping("/spare/in")
@RequiredArgsConstructor
public class SpareInController {

    private final SpareOutService spareOutService;

    /** 发起入库申请（设备管理员） */
    @PostMapping("/request")
    @Log(module = "备品备件", action = "发起入库申请")
    public R<SpareOutRequest> requestIn(@RequestBody InRequest req) {
        return R.ok("入库申请已提交，等待审批", spareOutService.requestIn(req.getItems(), req.getRemark()));
    }

    @Data
    public static class InRequest {
        @NotEmpty(message = "入库明细不能为空")
        private List<Map<String, Object>> items;
        private String remark;
    }
}
