package com.netsight.modules.notice.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.aspectj.Log;
import com.netsight.modules.notice.entity.Notice;
import com.netsight.modules.notice.service.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知公告控制器
 * 管理端：公告 CRUD + 发布/下线（notice:* 权限）
 * 用户端：已发布列表 + 未读数 + 详情标记已读（四类角色均可访问）
 */
@Slf4j
@RestController
@RequestMapping("/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    /**
     * 管理端：公告分页查询
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:list')")
    @GetMapping("/list")
    public R<PageResult<Notice>> list(@RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize,
                                      @RequestParam(required = false) String title,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) String noticeType) {
        return R.ok(noticeService.pageNotice(pageNum, pageSize, title, status, noticeType));
    }

    /**
     * 用户端：已发布公告列表（含是否已读）
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/published")
    public R<List<Map<String, Object>>> published() {
        return R.ok(noticeService.listPublished());
    }

    /**
     * 用户端：未读公告数（顶部铃铛角标）
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(noticeService.unreadCount());
    }

    /**
     * 公告详情（自动标记已读）
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public R<Notice> detail(@PathVariable Long id) {
        return R.ok(noticeService.getDetail(id));
    }

    /**
     * 管理端：公告统计
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:list')")
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(noticeService.stats());
    }

    /**
     * 新增公告
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:add')")
    @PostMapping
    @Log(module = "通知公告", action = "新增公告")
    public R<Void> add(@RequestBody Notice notice) {
        noticeService.addNotice(notice);
        return R.ok();
    }

    /**
     * 修改公告
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:edit')")
    @PutMapping
    @Log(module = "通知公告", action = "修改公告")
    public R<Void> update(@RequestBody Notice notice) {
        noticeService.updateNotice(notice);
        return R.ok();
    }

    /**
     * 删除公告
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:remove')")
    @DeleteMapping("/{id}")
    @Log(module = "通知公告", action = "删除公告")
    public R<Void> delete(@PathVariable Long id) {
        noticeService.deleteNotice(id);
        return R.ok();
    }

    /**
     * 发布公告
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:publish')")
    @PostMapping("/{id}/publish")
    @Log(module = "通知公告", action = "发布公告")
    public R<Void> publish(@PathVariable Long id) {
        noticeService.publish(id);
        return R.ok();
    }

    /**
     * 下线公告
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('notice:publish')")
    @PostMapping("/{id}/offline")
    @Log(module = "通知公告", action = "下线公告")
    public R<Void> offline(@PathVariable Long id) {
        noticeService.offline(id);
        return R.ok();
    }
}
