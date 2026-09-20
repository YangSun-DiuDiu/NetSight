package com.netsight.modules.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netsight.common.core.PageResult;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.NotificationLog;
import com.netsight.modules.alert.mapper.NotificationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 通知发送日志服务
 * 每次通道发送结果持久化，支持后台审计查询与失败回溯
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogMapper logMapper;

    /**
     * 分页查询发送日志（租户隔离）
     */
    public PageResult<NotificationLog> pageLog(long pageNum, long pageSize, String eventType, String channelType, Integer success) {
        Page<NotificationLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<NotificationLog> wrapper = new LambdaQueryWrapper<NotificationLog>()
                .like(StringUtils.hasText(eventType), NotificationLog::getEventType, eventType)
                .eq(StringUtils.hasText(channelType), NotificationLog::getChannelType, channelType)
                .eq(success != null, NotificationLog::getSuccess, success);
        if (!SecurityUtils.isSuperAdmin()) {
            wrapper.eq(NotificationLog::getTenantId, SecurityUtils.getTenantId());
        }
        wrapper.orderByDesc(NotificationLog::getId);
        Page<NotificationLog> result = logMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /**
     * 事件对应的发送日志数（列表回显）
     */
    public long countByEvent(Long eventId) {
        return logMapper.selectCount(new LambdaQueryWrapper<NotificationLog>()
                .eq(NotificationLog::getEventId, eventId));
    }

    /**
     * 按事件ID列表批量查询日志（避免循环查库）
     */
    public List<NotificationLog> listByEventIds(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        return logMapper.selectList(new LambdaQueryWrapper<NotificationLog>()
                .in(NotificationLog::getEventId, eventIds));
    }
}
