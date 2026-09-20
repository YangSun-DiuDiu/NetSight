package com.netsight.modules.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.notice.entity.Notice;
import com.netsight.modules.notice.entity.NoticeRead;
import com.netsight.modules.notice.mapper.NoticeMapper;
import com.netsight.modules.notice.mapper.NoticeReadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知公告服务
 * 公告 CRUD（多租户隔离）+ 发布/下线 + 已读/未读统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeMapper noticeMapper;
    private final NoticeReadMapper noticeReadMapper;

    /**
     * 管理端分页查询（非超管仅本租户）
     */
    public PageResult<Notice> pageNotice(long pageNum, long pageSize, String title, Integer status, String noticeType) {
        Page<Notice> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .like(StringUtils.hasText(title), Notice::getTitle, title)
                .eq(status != null, Notice::getStatus, status)
                .eq(StringUtils.hasText(noticeType), Notice::getNoticeType, noticeType);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(Notice::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(Notice::getIsTop).orderByDesc(Notice::getPublishTime).orderByDesc(Notice::getId);
        Page<Notice> result = noticeMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 用户端已发布公告列表（置顶优先、发布时间倒序），附带当前用户是否已读
     */
    public List<Map<String, Object>> listPublished() {
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .eq(Notice::getStatus, 1)
                .and(w -> w.isNull(Notice::getExpireTime).or().gt(Notice::getExpireTime, LocalDateTime.now()));
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(Notice::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(Notice::getIsTop).orderByDesc(Notice::getPublishTime).orderByDesc(Notice::getId);
        List<Notice> notices = noticeMapper.selectList(wrapper);

        // 当前用户已读集合
        Set<Long> readIds = new HashSet<>();
        if (SecurityUtils.getUserId() != null) {
            LambdaQueryWrapper<NoticeRead> readWrapper = new LambdaQueryWrapper<NoticeRead>()
                    .eq(NoticeRead::getUserId, SecurityUtils.getUserId());
            if (!SecurityUtils.isSuperAdmin()) {
                readWrapper.eq(NoticeRead::getTenantId, SecurityUtils.getTenantId());
            }
            readIds = noticeReadMapper.selectList(readWrapper).stream()
                    .map(NoticeRead::getNoticeId).collect(Collectors.toSet());
        }
        Set<Long> finalReadIds = readIds;
        return notices.stream().map(n -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", n.getId());
            m.put("title", n.getTitle());
            m.put("content", n.getContent());
            m.put("noticeType", n.getNoticeType());
            m.put("level", n.getLevel());
            m.put("isTop", n.getIsTop());
            m.put("publishTime", n.getPublishTime());
            m.put("publisherName", n.getPublisherName());
            m.put("readFlag", finalReadIds.contains(n.getId()) ? 1 : 0);
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 当前用户未读公告数（顶部铃铛角标）
     */
    public long unreadCount() {
        List<Map<String, Object>> published = listPublished();
        long total = published.size();
        long read = published.stream().filter(m -> ((Number) m.get("readFlag")).intValue() == 1).count();
        return total - read;
    }

    /**
     * 公告详情 + 标记已读（仅已发布公告记录已读）
     */
    @Transactional(rollbackFor = Exception.class)
    public Notice getDetail(Long id) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告不存在");
        }
        checkTenantPermission(notice.getTenantId());
        // 已发布且未过期才标记已读（草稿/已下线不记录）
        boolean visible = notice.getStatus() == 1
                && (notice.getExpireTime() == null || notice.getExpireTime().isAfter(LocalDateTime.now()));
        if (visible && SecurityUtils.getUserId() != null) {
            markRead(notice);
        }
        return notice;
    }

    /**
     * 新增公告（默认草稿状态）
     */
    public void addNotice(Notice notice) {
        if (!StringUtils.hasText(notice.getTitle())) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告标题不能为空");
        }
        if (notice.getTenantId() == null) {
            notice.setTenantId(SecurityUtils.getTenantId());
        }
        if (notice.getStatus() == null) {
            notice.setStatus(0);
        }
        if (notice.getIsTop() == null) {
            notice.setIsTop(0);
        }
        if (notice.getReadCount() == null) {
            notice.setReadCount(0);
        }
        noticeMapper.insert(notice);
    }

    /**
     * 修改公告（仅本租户，超管不限；已发布公告修改后保持原状态）
     */
    public void updateNotice(Notice notice) {
        Notice exist = noticeMapper.selectById(notice.getId());
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告不存在");
        }
        checkTenantPermission(exist.getTenantId());
        notice.setTenantId(exist.getTenantId());
        noticeMapper.updateById(notice);
    }

    /**
     * 删除公告（级联删除已读记录）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNotice(Long id) {
        Notice exist = noticeMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告不存在");
        }
        checkTenantPermission(exist.getTenantId());
        noticeMapper.deleteById(id);
        // 级联清理已读记录
        noticeReadMapper.delete(new LambdaQueryWrapper<NoticeRead>().eq(NoticeRead::getNoticeId, id));
    }

    /**
     * 发布公告（草稿/已下线 → 已发布，记录发布人与发布时间）
     */
    public void publish(Long id) {
        Notice exist = noticeMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告不存在");
        }
        checkTenantPermission(exist.getTenantId());
        Notice update = new Notice();
        update.setId(id);
        update.setStatus(1);
        update.setPublishTime(LocalDateTime.now());
        update.setPublisherId(SecurityUtils.getUserId());
        update.setPublisherName(SecurityUtils.getLoginUser().getUsername());
        noticeMapper.updateById(update);
    }

    /**
     * 下线公告（已发布 → 已下线）
     */
    public void offline(Long id) {
        Notice exist = noticeMapper.selectById(id);
        if (exist == null) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "公告不存在");
        }
        checkTenantPermission(exist.getTenantId());
        Notice update = new Notice();
        update.setId(id);
        update.setStatus(2);
        noticeMapper.updateById(update);
    }

    /**
     * 统计（总数/已发布/草稿/已下线）
     */
    public Map<String, Object> stats() {
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(Notice::getTenantId, SecurityUtils.getTenantId());
        }
        long total = noticeMapper.selectCount(wrapper);
        long published = noticeMapper.selectCount(new LambdaQueryWrapper<Notice>().eq(Notice::getStatus, 1).eq(!SecurityUtils.isSuperAdmin(), Notice::getTenantId, SecurityUtils.getTenantId()));
        long draft = noticeMapper.selectCount(new LambdaQueryWrapper<Notice>().eq(Notice::getStatus, 0).eq(!SecurityUtils.isSuperAdmin(), Notice::getTenantId, SecurityUtils.getTenantId()));
        long offline = noticeMapper.selectCount(new LambdaQueryWrapper<Notice>().eq(Notice::getStatus, 2).eq(!SecurityUtils.isSuperAdmin(), Notice::getTenantId, SecurityUtils.getTenantId()));
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("published", published);
        result.put("draft", draft);
        result.put("offline", offline);
        return result;
    }

    /**
     * 标记已读（幂等：唯一键冲突忽略）
     */
    private void markRead(Notice notice) {
        try {
            NoticeRead read = new NoticeRead();
            read.setTenantId(notice.getTenantId());
            read.setNoticeId(notice.getId());
            read.setUserId(SecurityUtils.getUserId());
            read.setReadTime(LocalDateTime.now());
            noticeReadMapper.insert(read);
            // 冗余已读数 +1
            noticeMapper.update(null, new LambdaUpdateWrapper<Notice>()
                    .eq(Notice::getId, notice.getId())
                    .setSql("read_count = read_count + 1"));
        } catch (Exception e) {
            // 唯一键冲突 = 已读过，忽略
            log.debug("markRead duplicate ignored, noticeId={}, userId={}", notice.getId(), SecurityUtils.getUserId());
        }
    }

    /**
     * 数据隔离校验：非超管不能操作其他租户公告
     */
    private void checkTenantPermission(Long targetTenantId) {
        if (!SecurityUtils.isSuperAdmin() && !targetTenantId.equals(SecurityUtils.getTenantId())) {
            throw new ServiceException(ResultCode.FORBIDDEN);
        }
    }
}
