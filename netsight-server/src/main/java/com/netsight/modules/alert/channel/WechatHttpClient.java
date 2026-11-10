package com.netsight.modules.alert.channel;

import java.util.Map;

/**
 * 微信接口 HTTP 客户端抽象，便于单元测试注入 mock。
 */
public interface WechatHttpClient {

    String get(String url, Map<String, Object> params, int timeoutMs);

    String postJson(String url, String body, int timeoutMs);
}
