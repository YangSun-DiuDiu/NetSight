package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WechatTemplatePayloadTest {

    private final WechatTemplatePayload payload =
            new WechatTemplatePayload(JsonMapper.builder().build());

    @Test
    void parsesJsonAndRendersFields() {
        String content = """
                {"templateId":"tpl-1","url":"http://x","fields":[
                  {"key":"thing2","value":"{{device_name}}","default":"未知设备"},
                  {"key":"phrase9","value":"{{event_state}}","default":"告警"}
                ]}
                """;
        SendRequest req = SendRequest.builder()
                .content(content)
                .contentVars(Map.of("device_name", "核心交换机", "event_type", "device_offline"))
                .bizId("b1").tenantId(7L).build();
        WechatTemplatePayload.Rendered r = payload.render(req, "fallback");
        assertEquals("tpl-1", r.templateId());
        assertEquals("核心交换机", r.data().get("thing2").get("value"));
        assertEquals("设备离线", r.data().get("phrase9").get("value"));
    }

    @Test
    void emptyContentFallsBackToDefaultFiveFields() {
        SendRequest req = SendRequest.builder().content("").contentVars(Map.of()).build();
        WechatTemplatePayload.Rendered r = payload.render(req, "tpl-43");
        assertEquals("tpl-43", r.templateId());
        assertEquals(5, r.data().size());
    }

    @Test
    void truncatesByCodePoint() {
        assertEquals("12345", WechatTemplatePayload.cut("123456", 5));
        assertEquals(5, WechatTemplatePayload.cut("😀😀😀😀😀😀", 5)
                .codePointCount(0, WechatTemplatePayload.cut("😀😀😀😀😀😀", 5).length()));
    }

    @Test
    void severityMaps() {
        assertEquals("严重", WechatTemplatePayload.severityText("critical"));
        assertEquals("重要", WechatTemplatePayload.severityText("warning"));
        assertEquals("一般", WechatTemplatePayload.severityText("info"));
    }
}
