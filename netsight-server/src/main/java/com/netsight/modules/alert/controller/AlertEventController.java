package com.netsight.modules.alert.controller;

import com.netsight.common.core.PageResult;
import com.netsight.common.core.R;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.alert.entity.EventRecord;
import com.netsight.modules.alert.entity.NotifyChannel;
import com.netsight.modules.alert.mapper.NotifyChannelMapper;
import com.netsight.modules.alert.service.EventCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.netsight.framework.aspectj.Log;
import com.netsight.framework.aspectj.RateLimit;

/**
 * 事件中心控制器（告警中心 - 事件记录）
 */
@Slf4j
@RestController
@RequestMapping("/alert/event")
@RequiredArgsConstructor
public class AlertEventController {

    private final EventCenterService eventCenterService;
    private final NotifyChannelMapper notifyChannelMapper;

    /**
     * 事件分页查询
     * GET /alert/event/list?pageNum=1&pageSize=10&eventType=&severity=&status=
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:event:list')")
    @GetMapping("/list")
    public R<PageResult<EventRecord>> list(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String eventType,
                                           @RequestParam(required = false) String severity,
                                           @RequestParam(required = false) String status) {
        return R.ok(eventCenterService.pageEvent(pageNum, pageSize, eventType, severity, status));
    }

    /**
     * 操作日志分页查询
     * GET /alert/event/operation-log?pageNum=1&pageSize=10&keyword=
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:event:operation')")
    @GetMapping("/operation-log")
    public R<PageResult<EventRecord>> operationLog(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(required = false) String keyword) {
        return R.ok(eventCenterService.pageOperationLog(pageNum, pageSize, keyword));
    }

    /**
     * 手动触发事件（管理员主动发送通知，可选通道/联系人/内容）
     * POST /alert/event/manual
     * Body: {"eventType":"manual_notify","content":"...","contactIds":[1,2],"channels":["sms","wechat","pushplus"]}
     * contactIds 为通知联系人 ID（按通道分别解析：短信→手机号、公众号→openid；仅 pushplus 通道时可留空）
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('alert:event:manual')")
    @PostMapping("/manual")
    @Log(module="告警中心", action="手动发送告警")
    @RateLimit(key = "ip", limit = 20, window = 60)
    public R<Long> manual(@RequestBody Map<String, Object> body) {
        String eventType = body.get("eventType") == null ? "manual_notify" : String.valueOf(body.get("eventType"));
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        String bizId = body.get("bizId") == null ? null : String.valueOf(body.get("bizId"));
        List<Long> contactIds = castLongList(body.get("contactIds"));
        List<Long> channels = castLongList(body.get("channels"));
        if (channels.isEmpty()) {
            return R.fail(400, "通知通道不能为空");
        }
        // 所选渠道实例中是否包含 pushplus（pushplus 按租户 token 群发，接收人可为空）
        boolean hasPushplus = channels.stream()
                .map(notifyChannelMapper::selectById)
                .anyMatch(c -> c != null && "pushplus".equals(c.getChannelType()));
        if (contactIds.isEmpty() && !hasPushplus) {
            return R.fail(400, "请选择通知联系人（仅推送 PushPlus 通道时可不选）");
        }
        Long eventId = eventCenterService.manualEvent(
                SecurityUtils.getTenantId(), eventType, bizId, content, contactIds, channels);
        return R.ok("手动通知已发送", eventId);
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private List<Long> castLongList(Object obj) {
        if (obj instanceof List<?> list) {
            List<Long> result = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n) {
                    result.add(n.longValue());
                } else {
                    try {
                        result.add(Long.valueOf(String.valueOf(o)));
                    } catch (Exception ignored) {
                    }
                }
            }
            return result;
        }
        return List.of();
    }
}
