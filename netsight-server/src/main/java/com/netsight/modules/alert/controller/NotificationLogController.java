package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.service.NotificationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发送日志控制器（告警中心 - 发送日志）
 */
@Slf4j
@RestController
@RequestMapping("/alert/log")
@RequiredArgsConstructor
public class NotificationLogController {

    private final NotificationLogService logService;

    /**
     * 发送日志分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:log:list')")
    @GetMapping("/list")
    public R<PageResult<NotificationLog>> list(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String eventType,
                                               @RequestParam(required = false) String channelType,
                                               @RequestParam(required = false) Integer success) {
        return R.ok(logService.pageLog(pageNum, pageSize, eventType, channelType, success));
    }
}
