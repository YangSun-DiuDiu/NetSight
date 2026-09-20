package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.service.NotificationContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通知联系人控制器
 * 管理端：联系人 CRUD + 人员多选下拉（alert:contact:* 权限，role1/2/3）
 */
@Slf4j
@RestController
@RequestMapping("/alert/contact")
@RequiredArgsConstructor
public class NotificationContactController {

    private final NotificationContactService contactService;

    /**
     * 分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:list')")
    @GetMapping("/list")
    public R<PageResult<NotificationContact>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(required = false) String mobile,
                                                  @RequestParam(required = false) Integer status) {
        return R.ok(contactService.pageContact(pageNum, pageSize, name, mobile, status));
    }

    /**
     * 人员多选下拉（规则/手动发送接收人选择用）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:list')")
    @GetMapping("/options")
    public R<List<NotificationContact>> options() {
        return R.ok(contactService.options());
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:add')")
    @PostMapping
    @Log(module = "通知联系人", action = "新增联系人")
    public R<Void> add(@RequestBody NotificationContact contact) {
        contactService.addContact(contact);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:edit')")
    @PutMapping
    @Log(module = "通知联系人", action = "修改联系人")
    public R<Void> update(@RequestBody NotificationContact contact) {
        contactService.updateContact(contact);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:contact:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "通知联系人", action = "删除联系人")
    public R<Void> delete(@PathVariable Long id) {
        contactService.deleteContact(id);
        return R.ok();
    }
}
