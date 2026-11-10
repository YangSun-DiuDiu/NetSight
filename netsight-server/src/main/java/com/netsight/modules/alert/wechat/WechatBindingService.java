package com.netsight.modules.alert.wechat;

import com.netsight.common.core.ResultCode;
import com.netsight.common.exception.ServiceException;
import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.service.NotificationContactService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * 微信 openid 绑定服务。
 * 硬约束：手机号查不到通知联系人时绝不自动创建，只绑定已登记且启用的联系人；
 * 绑定失败只返回原因，不影响登录本身。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatBindingService {

    public static final String TICKET_KEY = "netsight:wechat:bind:ticket:";

    private final NotificationContactService contactService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 登录后按手机号绑定：ticket 由 OAuth 回调生成，内含 openid/appId/channelId。
     */
    public WechatBindResult bindByTicket(Long tenantId, String phone, String ticket) {
        Ticket t = readAndDeleteTicket(ticket);
        if (t == null) {
            return result(false, "TICKET_EXPIRED", "绑定凭证已过期，请重新从公众号进入。");
        }
        if (tenantId == null || !StringUtils.hasText(phone)) {
            return result(false, "BIND_ERROR", "登录信息不完整，无法绑定微信。");
        }
        List<NotificationContact> contacts = contactService.findByTenantAndPhone(tenantId, phone);
        if (contacts.isEmpty()) {
            // 硬约束：不插入、不绑定，提示联系管理员
            return result(false, "CONTACT_NOT_REGISTERED",
                    "当前手机号未登记为通知联系人，请联系管理员在【告警中心 → 通知联系人】中添加并启用后，再重新从公众号进入绑定。");
        }
        List<NotificationContact> enabled = contacts.stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .toList();
        if (enabled.isEmpty()) {
            return result(false, "CONTACT_DISABLED", "该通知联系人已停用，请联系管理员启用后再绑定。");
        }
        if (enabled.size() > 1) {
            return result(false, "CONTACT_AMBIGUOUS", "该手机号存在多个通知联系人，请联系管理员确认唯一联系人。");
        }
        return bindToContact(enabled.get(0), tenantId, t.getOpenid(), t.getAppId());
    }

    /**
     * 管理员二维码路径：直接绑定指定联系人（回调无登录态，用显式 tenantId）。
     */
    public WechatBindResult bindByContact(Long contactId, Long tenantId, String openid, String appId) {
        if (contactId == null || tenantId == null || !StringUtils.hasText(openid)) {
            return result(false, "BIND_ERROR", "绑定参数不完整。");
        }
        NotificationContact contact;
        try {
            contact = contactService.getByIdForTenant(contactId, tenantId);
        } catch (ServiceException e) {
            return result(false, "BIND_ERROR", "联系人不存在或不属于当前租户。");
        }
        return bindToContact(contact, tenantId, openid, appId);
    }

    private WechatBindResult bindToContact(NotificationContact contact, Long tenantId,
                                           String openid, String appId) {
        if (contact.getStatus() == null || contact.getStatus() != 1) {
            return result(false, "CONTACT_DISABLED", "该通知联系人已停用，请联系管理员启用后再绑定。");
        }
        if (!StringUtils.hasText(openid)) {
            return result(false, "BIND_ERROR", "未获取到微信 openid，请重新授权。");
        }
        NotificationContact sameOpenid = contactService.findByTenantAndOpenid(tenantId, openid, appId);
        if (sameOpenid != null && !sameOpenid.getId().equals(contact.getId())) {
            return result(false, "OPENID_BOUND_OTHER", "该微信已绑定其他通知联系人，请联系管理员处理。");
        }
        if (StringUtils.hasText(contact.getWechatOpenid())
                && !openid.equals(contact.getWechatOpenid())) {
            return result(false, "CONTACT_BOUND_OTHER", "该通知联系人已绑定其他微信，请联系管理员先解绑。");
        }
        contactService.bindWechat(contact.getId(), openid, appId);
        log.info("微信绑定成功 tenantId={} contactId={} appId={}", tenantId, contact.getId(), appId);
        return result(true, null, "登录成功，微信消息已绑定。");
    }

    private Ticket readAndDeleteTicket(String ticket) {
        if (!StringUtils.hasText(ticket)) {
            return null;
        }
        try {
            String key = TICKET_KEY + ticket;
            String json = redis.opsForValue().get(key);
            redis.delete(key);
            return json == null ? null : objectMapper.readValue(json, Ticket.class);
        } catch (Exception e) {
            log.warn("读取微信绑定 ticket 失败: {}", e.getMessage());
            return null;
        }
    }

    private WechatBindResult result(boolean bound, String reason, String message) {
        return WechatBindResult.builder().bound(bound).reason(reason).message(message).build();
    }

    /** Redis 中的一次性绑定凭证 */
    @Data
    public static class Ticket {
        private String openid;
        private String appId;
        private String unionid;
        private Long channelId;
    }

    /** 供 OAuth 服务写入 ticket */
    public void saveTicket(String ticket, String openid, String appId, String unionid, Long channelId) {
        Ticket t = new Ticket();
        t.setOpenid(openid);
        t.setAppId(appId);
        t.setUnionid(unionid);
        t.setChannelId(channelId);
        try {
            redis.opsForValue().set(TICKET_KEY + ticket,
                    objectMapper.writeValueAsString(t), Duration.ofMinutes(10));
        } catch (Exception e) {
            throw new ServiceException(ResultCode.PARAM_ERROR.getCode(), "生成微信绑定凭证失败");
        }
    }
}
