package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dev/test 专用 PushPlus 模拟发送器：不依赖租户 Token，不调用外网。 */
class MockPushplusChannelSenderTest {

    @Test
    void succeedsWithoutTenantTokenOrNetworkCall() {
        MockPushplusChannelSender sender = new MockPushplusChannelSender();
        SendRequest req = SendRequest.builder()
                .tenantId(7L)
                .receiverList(List.of("receiver-1"))
                .content("测试内容")
                .channelConfig(Map.of())
                .build();

        SendResult result = sender.send(req);

        assertTrue(result.isSuccess());
        assertTrue(result.getThirdPartyMsgId().startsWith("MOCK-PP-"));
        assertEquals("pushplus", sender.getChannelType());
    }
}
