package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.alert.entity.NotificationRule;
import com.netsight.modules.alert.service.NotificationRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.netsight.framework.aspectj.Log;

/**
 * 通知规则控制器（告警中心 - 通知规则/自动发送策略）
 * 管理员配置：事件类型 → 触发条件 → 接收人 → 通道 → 模板
 */
@Slf4j
@RestController
@RequestMapping("/alert/rule")
@RequiredArgsConstructor
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    /**
     * 规则分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:rule:list')")
    @GetMapping("/list")
    public R<PageResult<NotificationRule>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestParam(required = false) String ruleName,
                                                @RequestParam(required = false) String eventType,
                                                @RequestParam(required = false) Integer enabled) {
        return R.ok(ruleService.pageRule(pageNum, pageSize, ruleName, eventType, enabled));
    }

    /**
     * 新增规则
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:rule:add')")
    @PostMapping
    @Log(module="通知规则", action="新增通知规则")
    public R<Void> add(@RequestBody NotificationRule rule) {
        ruleService.addRule(rule);
        return R.ok();
    }

    /**
     * 修改规则
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:rule:edit')")
    @PutMapping
    @Log(module="通知规则", action="修改通知规则")
    public R<Void> update(@RequestBody NotificationRule rule) {
        ruleService.updateRule(rule);
        return R.ok();
    }

    /**
     * 删除规则
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:rule:remove')")
    @DeleteMapping("/{id}")
    @Log(module="通知规则", action="删除通知规则")
    public R<Void> delete(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return R.ok();
    }
}
