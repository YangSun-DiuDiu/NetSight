package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.modules.alert.entity.NotificationTemplate;
import com.netsight.modules.alert.service.NotificationTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.netsight.framework.aspectj.Log;

/**
 * 消息模板控制器（告警中心 - 消息模板）
 */
@Slf4j
@RestController
@RequestMapping("/alert/template")
@RequiredArgsConstructor
public class NotificationTemplateController {

    private final NotificationTemplateService templateService;

    /**
     * 模板分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:template:list')")
    @GetMapping("/list")
    public R<PageResult<NotificationTemplate>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(required = false) String templateName,
                                                    @RequestParam(required = false) String channelType) {
        return R.ok(templateService.pageTemplate(pageNum, pageSize, templateName, channelType));
    }

    /**
     * 模板下拉（新增规则时选择模板用）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:rule:list') or hasAuthority('alert:template:list')")
    @GetMapping("/options")
    public R<List<NotificationTemplate>> options(@RequestParam(required = false) String channelType) {
        return R.ok(templateService.listOptions(channelType));
    }

    /**
     * 新增模板
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:template:add')")
    @PostMapping
    @Log(module="消息模板", action="新增消息模板")
    public R<Void> add(@RequestBody NotificationTemplate template) {
        templateService.addTemplate(template);
        return R.ok();
    }

    /**
     * 修改模板
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:template:edit')")
    @PutMapping
    @Log(module="消息模板", action="修改消息模板")
    public R<Void> update(@RequestBody NotificationTemplate template) {
        templateService.updateTemplate(template);
        return R.ok();
    }

    /**
     * 删除模板
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:template:remove')")
    @DeleteMapping("/{id}")
    @Log(module="消息模板", action="删除消息模板")
    public R<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return R.ok();
    }
}
