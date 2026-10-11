package com.netsight.modules.ai;

import com.netsight.modules.ai.dto.AIRequest;

/**
 * AI 服务接口（与通知通道 NotificationChannelSender 同构的 SPI）
 * <p>
 * 设计原则：能力与模型解耦——业务能力（知识库问诊等）只依赖本接口，
 * 具体模型供应商（DeepSeek/豆包/通义/私有化）由实现类承载，换厂商不改业务代码。
 */
public interface AIService {

    /**
     * 单轮补全（摘要/问答统一入口），返回模型文本。
     *
     * @param request 请求（system+user prompt、温度、token 上限、租户、能力标识）
     * @return 模型生成的文本
     * @throws AIServiceException 模型不可用/超时/返回异常
     */
    String complete(AIRequest request);

    /**
     * 当前 AI 是否可用（总开关 + API Key 已配置）
     */
    boolean enabled();
}
