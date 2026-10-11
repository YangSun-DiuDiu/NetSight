package com.netsight.modules.ai.controller;

import com.netsight.common.core.R;
import com.netsight.framework.aspectj.RateLimit;
import com.netsight.modules.ai.KnowledgeAskService;
import com.netsight.modules.ai.dto.AiAskRequest;
import com.netsight.modules.ai.dto.AiAskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力控制器（阶段 1：知识库问诊）
 * <p>
 * 接口：POST /ai/knowledge/ask
 * - 登录用户可用（与知识库查阅同权限：knowledge:fault:view），按当前租户隔离检索
 * - 限流：60 秒内同 IP 最多 10 次（防刷成本）
 * - 降级：模型不可用/失败时返回知识库原文，不影响正常访问
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final KnowledgeAskService knowledgeAskService;

    /**
     * 知识库 AI 问诊（RAG）
     * Body: {question(必填), deviceName, deviceType, deviceLocation, faultType, description(可选)}
     * 返回: {answer, sources[{id,title,category}], fromCache, aiUsed, noHit}
     */
    @PreAuthorize("hasRole('super_admin') or hasAuthority('knowledge:fault:view')")
    @RateLimit(limit = 10, window = 60, message = "AI 问诊过于频繁，请1分钟后再试")
    @PostMapping("/knowledge/ask")
    public R<AiAskResult> ask(@RequestBody AiAskRequest request) {
        return R.ok(knowledgeAskService.ask(request));
    }
}
