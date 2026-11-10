package com.netsight.modules.alert.channel;

import cn.hutool.http.HttpUtil;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认 HTTP 实现，基于 Hutool。
 */
@Component
public class HutoolWechatHttpClient implements WechatHttpClient {

    @Override
    public String get(String url, Map<String, Object> params, int timeoutMs) {
        return HttpUtil.get(url, params, timeoutMs);
    }

    @Override
    public String postJson(String url, String body, int timeoutMs) {
        return HttpUtil.post(url, body, timeoutMs);
    }
}
