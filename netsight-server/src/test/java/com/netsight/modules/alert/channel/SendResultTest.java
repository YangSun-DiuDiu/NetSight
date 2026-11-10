package com.netsight.modules.alert.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SendResultTest {

    @Test
    void sendResultCarriesRecipientResults() {
        RecipientResult r = RecipientResult.builder()
                .receiver("o6****1234").success(true).retryCount(1).build();
        SendResult result = SendResult.builder()
                .success(false).retryable(false).templateId("tpl")
                .recipientResults(List.of(r)).build();
        assertEquals("tpl", result.getTemplateId());
        assertEquals(1, result.getRecipientResults().size());
        assertFalse(result.isRetryable());
    }
}
