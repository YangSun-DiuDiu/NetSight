package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dev/test 专用微信模拟发送器：不触达微信接口，逐人返回成功。 */
class MockWechatMpChannelSenderTest {

    private MockWechatMpChannelSender sender() {
        return new MockWechatMpChannelSender(
                new WechatTemplatePayload(JsonMapper.builder().build()));
    }

    @Test
    void returnsOneSuccessResultPerReceiverWithoutCallingWechat() {
        SendRequest req = SendRequest.builder()
                .receiverList(List.of("openid-1", "openid-2"))
                .channelConfig(Map.of("appId", "app", "appSecret", "sec", "templateId", "tpl"))
                .contentVars(Map.of("device_name", "dev"))
                .build();

        SendResult result = sender().send(req);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getRecipientResults().size());
        assertEquals("MOCK", result.getThirdPartyMsgId());
        assertEquals("tpl", result.getTemplateId());
    }

    @Test
    void rejectsMissingCredentials() {
        SendRequest req = SendRequest.builder()
                .receiverList(List.of("openid-1"))
                .channelConfig(Map.of("templateId", "tpl"))
                .build();

        SendResult result = sender().send(req);

        assertEquals(false, result.isSuccess());
    }
}
