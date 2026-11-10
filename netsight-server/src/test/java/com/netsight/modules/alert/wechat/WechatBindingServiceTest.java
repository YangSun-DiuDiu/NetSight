package com.netsight.modules.alert.wechat;

import com.netsight.modules.alert.entity.NotificationContact;
import com.netsight.modules.alert.service.NotificationContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用轻量 Fake 替代 Mockito，避免 JDK21 下 ByteBuddy 自附加偶发失败。
 */
class WechatBindingServiceTest {

    private FakeRedis redis;
    private FakeContactService contactService;
    private WechatBindingService service;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        contactService = new FakeContactService();
        service = new WechatBindingService(contactService, redis, JsonMapper.builder().build());
        redis.put("netsight:wechat:bind:ticket:ticket-1",
                "{\"openid\":\"openid-1\",\"appId\":\"wx-app\",\"channelId\":7}");
    }

    @Test
    void missingContactDoesNotCreateRow() {
        WechatBindResult r = service.bindByTicket(7L, "13900000002", "ticket-1");
        assertFalse(r.isBound());
        assertEquals("CONTACT_NOT_REGISTERED", r.getReason());
        assertFalse(contactService.bound);
    }

    @Test
    void disabledContactIsNotBound() {
        contactService.contacts = List.of(contact(3L, 0, null));
        WechatBindResult r = service.bindByTicket(7L, "13900000002", "ticket-1");
        assertFalse(r.isBound());
        assertEquals("CONTACT_DISABLED", r.getReason());
        assertFalse(contactService.bound);
    }

    @Test
    void ambiguousContactsAreNotBound() {
        contactService.contacts = List.of(contact(3L, 1, null), contact(4L, 1, null));
        WechatBindResult r = service.bindByTicket(7L, "13900000002", "ticket-1");
        assertFalse(r.isBound());
        assertEquals("CONTACT_AMBIGUOUS", r.getReason());
        assertFalse(contactService.bound);
    }

    @Test
    void singleEnabledContactIsBound() {
        contactService.contacts = List.of(contact(3L, 1, null));
        WechatBindResult r = service.bindByTicket(7L, "13900000002", "ticket-1");
        assertTrue(r.isBound());
        assertTrue(contactService.bound);
        assertEquals(3L, contactService.boundContactId);
        assertEquals("openid-1", contactService.boundOpenid);
        assertEquals("wx-app", contactService.boundAppId);
    }

    @Test
    void expiredTicketFailsWithoutBinding() {
        WechatBindResult r = service.bindByTicket(7L, "13900000002", "expired");
        assertFalse(r.isBound());
        assertEquals("TICKET_EXPIRED", r.getReason());
        assertFalse(contactService.bound);
    }

    private NotificationContact contact(Long id, int status, String openid) {
        NotificationContact c = new NotificationContact();
        c.setId(id);
        c.setTenantId(7L);
        c.setStatus(status);
        c.setWechatOpenid(openid);
        return c;
    }

    /** 只实现绑定用到的查询，避免触达真实 Mapper。 */
    static class FakeContactService extends NotificationContactService {
        List<NotificationContact> contacts = List.of();
        boolean bound;
        Long boundContactId;
        String boundOpenid;
        String boundAppId;

        FakeContactService() {
            super(null);
        }

        @Override
        public List<NotificationContact> findByTenantAndPhone(Long tenantId, String phone) {
            return contacts;
        }

        @Override
        public NotificationContact findByTenantAndPhoneUnique(Long tenantId, String phone) {
            return null;
        }

        @Override
        public NotificationContact findByTenantAndOpenid(Long tenantId, String openid, String appId) {
            return null;
        }

        @Override
        public void bindWechat(Long contactId, String openid, String appId) {
            this.bound = true;
            this.boundContactId = contactId;
            this.boundOpenid = openid;
            this.boundAppId = appId;
        }
    }

    /** 用反射 Proxy 实现 Redis 读写，不引入 Mockito。 */
    static class FakeRedis extends StringRedisTemplate {
        private final Map<String, String> store = new HashMap<>();

        void put(String key, String value) {
            store.put(key, value);
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "get":
                                return store.get(String.valueOf(args[0]));
                            case "set":
                                store.put(String.valueOf(args[0]), String.valueOf(args[1]));
                                return null;
                            case "toString":
                                return "FakeValueOps";
                            default:
                                return null;
                        }
                    });
        }

        @Override
        public Boolean delete(String key) {
            store.remove(key);
            return Boolean.TRUE;
        }
    }
}
