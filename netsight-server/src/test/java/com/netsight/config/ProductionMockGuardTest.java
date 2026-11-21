package com.netsight.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 生产防误开守卫：任一模拟开关开启即拒绝启动。 */
class ProductionMockGuardTest {

    private ProductionMockGuard guard(boolean smsMock, boolean wechatMock, boolean pushplusMock) {
        ProductionMockGuard guard = new ProductionMockGuard();
        ReflectionTestUtils.setField(guard, "smsMockMode", smsMock);
        ReflectionTestUtils.setField(guard, "wechatMockMode", wechatMock);
        ReflectionTestUtils.setField(guard, "pushplusMockMode", pushplusMock);
        return guard;
    }

    @Test
    void passesWhenAllMockSwitchesAreOff() {
        assertDoesNotThrow(() -> guard(false, false, false).verify());
    }

    @Test
    void failsWhenWechatMockIsOn() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard(false, true, false).verify());
        assertEquals("生产环境禁止开启模拟发送：netsight.alert.channel.wechat.mock-mode=true",
                exception.getMessage());
    }

    @Test
    void failsWhenPushplusMockIsOn() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard(false, false, true).verify());
        assertEquals("生产环境禁止开启模拟发送：netsight.alert.channel.pushplus.mock-mode=true",
                exception.getMessage());
    }

    @Test
    void failsWhenSmsMockIsOn() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> guard(true, false, false).verify());
        assertEquals("生产环境禁止开启模拟发送：netsight.sms.mock-mode=true",
                exception.getMessage());
    }
}
