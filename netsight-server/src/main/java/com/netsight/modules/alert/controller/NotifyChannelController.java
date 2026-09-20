package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.alert.entity.NotifyChannel;
import com.netsight.modules.alert.service.NotifyChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知渠道实例管理控制器
 * 管理端：渠道实例 CRUD + 渠道类型元数据（动态表单）+ 测试发送
 * 权限：alert:channel:*（role1/2 全量，role3 只读）
 */
@Slf4j
@RestController
@RequestMapping("/alert/channel")
@RequiredArgsConstructor
public class NotifyChannelController {

    private final NotifyChannelService channelService;

    /** 渠道类型元数据（前端按此渲染动态参数表单） */
    @GetMapping("/meta")
    public R<List<Map<String, Object>>> meta() {
        return R.ok(channelService.listChannelMeta());
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:list')")
    @GetMapping("/list")
    public R<PageResult<NotifyChannel>> list(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize,
                                             @RequestParam(required = false) String channelType,
                                             @RequestParam(required = false) String channelName) {
        return R.ok(channelService.page(pageNum, pageSize, channelType, channelName));
    }

    /** 渠道下拉选项（规则/手动发送选渠道实例用） */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:list')")
    @GetMapping("/options")
    public R<List<NotifyChannel>> options() {
        return R.ok(channelService.page(1, 1000, null, null).getRows());
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:list')")
    @GetMapping("/{id}")
    public R<NotifyChannel> detail(@PathVariable Long id) {
        return R.ok(channelService.detail(id));
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:add')")
    @PostMapping
    @Log(module = "通知渠道", action = "新增渠道")
    public R<Void> add(@RequestBody NotifyChannel channel) {
        channelService.add(channel);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:edit')")
    @PutMapping
    @Log(module = "通知渠道", action = "修改渠道")
    public R<Void> update(@RequestBody NotifyChannel channel) {
        channelService.update(channel);
        return R.ok();
    }

    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:channel:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "通知渠道", action = "删除渠道")
    public R<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return R.ok();
    }
}
