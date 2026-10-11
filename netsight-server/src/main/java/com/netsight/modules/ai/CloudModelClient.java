package com.netsight.modules.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.netsight.modules.ai.dto.AIRequest;
import com.netsight.modules.ai.entity.AIUsageLog;
import com.netsight.modules.ai.mapper.AIUsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * DeepSeek 云端模型适配器（AIService 默认实现）
 * <p>
 * 调用 OpenAI 兼容协议：POST {api-url}，Body
 * {@code {model, messages:[{role:system},{role:user}], temperature, max_tokens}}，
 * 响应取 {@code choices[0].message.content} 与 {@code usage}。
 * <p>
 * 关键设计：
 * <ul>
 *   <li><b>Key 走环境变量</b>：{@code DEEPSEEK_API_KEY} 由 systemd 注入，不落库不进 git；
 *       未配置时 enabled()=false，AI 能力自动降级（不阻塞告警/业务主链路）</li>
 *   <li><b>超时与重试</b>：connect 5s / read 30s；失败指数退避重试 2 次（1s/2s）；
 *       仍失败抛 {@link AIServiceException} 由应用层降级</li>
 *   <li><b>用量审计</b>：每次调用落 ai_usage_log（租户/用量/费用/状态），成本可控可查</li>
 *   <li><b>脱敏钩子</b>：mask-sensitive 开关开启时对设备名/IP 打码后再出域（生产建议开）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudModelClient implements AIService {

    /** AI 总开关（false 时 enabled()=false，全部能力降级，不调用模型） */
    @Value("${netsight.ai.enabled:true}")
    private boolean aiEnabled;

    /** DeepSeek API Key（环境变量注入，禁止硬编码；空=禁用） */
    @Value("${netsight.ai.api-key:}")
    private String apiKey;

    /** 模型接口地址（OpenAI 兼容） */
    @Value("${netsight.ai.api-url:https://api.deepseek.com/chat/completions}")
    private String apiUrl;

    /** 模型名 */
    @Value("${netsight.ai.model:deepseek-chat}")
    private String model;

    /** 连接超时（ms） */
    @Value("${netsight.ai.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    /** 读超时（ms） */
    @Value("${netsight.ai.read-timeout-ms:30000}")
    private int readTimeoutMs;

    /** 失败重试次数（指数退避 1s/2s） */
    @Value("${netsight.ai.max-retries:2}")
    private int maxRetries;

    /** 数据脱敏开关（设备名/IP 出域前打码，生产建议开启） */
    @Value("${netsight.ai.mask-sensitive:false}")
    private boolean maskSensitive;

    /** 输入价格（元/百万 tokens，deepseek-chat，可配置） */
    @Value("${netsight.ai.price-input-per-m:0.5}")
    private BigDecimal inputPricePerM;

    /** 输出价格（元/百万 tokens） */
    @Value("${netsight.ai.price-output-per-m:2}")
    private BigDecimal outputPricePerM;

    private final AIUsageLogMapper aiUsageLogMapper;

    @Override
    public boolean enabled() {
        return aiEnabled && StringUtils.hasText(apiKey);
    }

    @Override
    public String complete(AIRequest request) {
        if (!enabled()) {
            throw new AIServiceException("AI 服务未启用（总开关关闭或未配置 API Key）");
        }
        long start = System.currentTimeMillis();
        Exception lastError = null;
        // 指数退避重试：1s / 2s / 4s...
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String content = callOnce(request);
                log.info("【AI】租户={} feature={} 调用成功 耗时={}ms 重试={}",
                        request.getTenantId(), request.getFeature(),
                        System.currentTimeMillis() - start, attempt);
                return content;
            } catch (Exception e) {
                lastError = e;
                log.warn("【AI】租户={} feature={} 第{}次调用失败: {}",
                        request.getTenantId(), request.getFeature(), attempt + 1, e.getMessage());
                if (attempt < maxRetries) {
                    long sleepMs = (long) Math.pow(2, attempt) * 1000;
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new AIServiceException("AI 调用被中断", ie);
                    }
                }
            }
        }
        // 重试耗尽：记失败日志并抛异常
        recordUsage(request, "failed", null, 0, 0, String.valueOf(lastError == null ? "" : lastError.getMessage()));
        throw new AIServiceException("AI 模型调用失败: " + (lastError == null ? "未知错误" : lastError.getMessage()), lastError);
    }

    /**
     * 单次调用（不含重试）
     */
    private String callOnce(AIRequest request) {
        String userText = maskSensitive ? maskSensitiveText(request.getUserPrompt()) : request.getUserPrompt();

        JSONObject body = new JSONObject();
        body.set("model", model);
        JSONArray messages = new JSONArray();
        JSONObject systemMsg = new JSONObject();
        systemMsg.set("role", "system");
        systemMsg.set("content", request.getSystemPrompt() == null ? "" : request.getSystemPrompt());
        messages.add(systemMsg);
        JSONObject userMsg = new JSONObject();
        userMsg.set("role", "user");
        userMsg.set("content", userText);
        messages.add(userMsg);
        body.set("messages", messages);
        body.set("temperature", request.getTemperature() == null ? 0.3 : request.getTemperature());
        body.set("max_tokens", request.getMaxTokens() == null ? 800 : request.getMaxTokens());
        body.set("stream", false);

        long start = System.currentTimeMillis();
        String resp;
        try (HttpResponse response = HttpRequest.post(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body.toString())
                .timeout(connectTimeoutMs + readTimeoutMs)
                .execute()) {
            if (!response.isOk()) {
                throw new AIServiceException("HTTP " + response.getStatus() + " "
                        + StrUtil.maxLength(response.body(), 200));
            }
            resp = response.body();
        }
        // 响应较长只留关键信息进日志
        String logResp = resp != null && resp.length() > 500 ? resp.substring(0, 500) + "...(truncated)" : resp;
        log.debug("【AI】响应={}", logResp);

        JSONObject json = JSONUtil.parseObj(resp);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AIServiceException("模型返回为空: " + StrUtil.maxLength(resp, 200));
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getStr("content");
        if (!StringUtils.hasText(content)) {
            throw new AIServiceException("模型未返回有效内容");
        }

        // 用量审计（usage 可能缺失，缺省记 0）
        JSONObject usage = json.getJSONObject("usage");
        int promptTokens = usage == null ? 0 : usage.getInt("prompt_tokens", 0);
        int completionTokens = usage == null ? 0 : usage.getInt("completion_tokens", 0);
        long costMs = System.currentTimeMillis() - start;
        recordUsage(request, "success", model, promptTokens, completionTokens, null);
        log.info("【AI】租户={} feature={} 模型={} 输入={}tokens 输出={}tokens 耗时={}ms",
                request.getTenantId(), request.getFeature(), model, promptTokens, completionTokens, costMs);
        return content;
    }

    /**
     * 用量审计落库
     */
    private void recordUsage(AIRequest request, String status, String modelName,
                             int promptTokens, int completionTokens, String errorMsg) {
        try {
            AIUsageLog logEntity = new AIUsageLog();
            logEntity.setTenantId(request.getTenantId());
            logEntity.setFeature(request.getFeature());
            logEntity.setModel(modelName);
            logEntity.setPromptTokens(promptTokens);
            logEntity.setCompletionTokens(completionTokens);
            // 费用估算：输入价格×输入tokens/1M + 输出价格×输出tokens/1M
            BigDecimal cost = BigDecimal.valueOf(promptTokens)
                    .multiply(inputPricePerM)
                    .add(BigDecimal.valueOf(completionTokens).multiply(outputPricePerM))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
            logEntity.setCostYuan(cost);
            logEntity.setStatus(status);
            if (errorMsg != null && errorMsg.length() > 200) {
                errorMsg = errorMsg.substring(0, 200);
            }
            logEntity.setErrorMsg(errorMsg);
            logEntity.setCreateTime(LocalDateTime.now());
            aiUsageLogMapper.insert(logEntity);
        } catch (Exception e) {
            // 审计落库失败不影响主流程（仅记日志）
            log.error("AI 用量审计落库失败: {}", e.getMessage());
        }
    }

    /**
     * 数据脱敏：对疑似设备名/IP 打码（mask-sensitive=true 时生效）
     * 正则：IP 或 "X摄像头/主机/交换机/服务器/网关" 形态 → ***
     */
    private String maskSensitiveText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replaceAll("\\b(?:192\\.168\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|"
                + "[\\u4e00-\\u9fa5]{2,8}(?:摄像头|主机|交换机|服务器|网关|门禁))\\b", "***");
    }
}
