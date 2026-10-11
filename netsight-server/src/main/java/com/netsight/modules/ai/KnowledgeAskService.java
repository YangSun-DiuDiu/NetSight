package com.netsight.modules.ai;

import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.netsight.framework.security.SecurityUtils;
import com.netsight.modules.ai.dto.AIRequest;
import com.netsight.modules.ai.dto.AiAskRequest;
import com.netsight.modules.ai.dto.AiAskResult;
import com.netsight.modules.knowledge.entity.FaultArticle;
import com.netsight.modules.knowledge.mapper.FaultArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库 AI 问诊服务（RAG：检索 → 拼 prompt → 生成）
 * <p>
 * 流程：
 * <ol>
 *   <li><b>检索</b>：用户问题分词（去掉"怎么/如何/处理"等停用词）后与
 *       keywords/title/symptom LIKE 匹配，按浏览量排序取 topK 篇<b>当前租户</b>启用文章；
 *       无命中直接提示，不空跑模型</li>
 *   <li><b>拼 prompt</b>：system 定角色（NetSight 运维专家）与输出格式
 *       （可能原因 → 排查步骤 → 备件建议 → 参考文章）；user 放工单上下文
 *       （设备/位置/故障类型/描述 + 维修人员补充现象）+ 知识库条目</li>
 *   <li><b>生成</b>：调 {@link AIService#complete}，返回答案 + 引用文章</li>
 *   <li><b>缓存</b>：同租户同问题 10 分钟 Redis 缓存（省成本）；</li>
 *   <li><b>降级</b>：模型失败 → 直接返回 top1 文章原文 + 提示"AI 服务暂不可用"；
 *       模型不可用 → 同样走原文兜底，业务链路不受影响</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAskService {

    /** 检索条数 */
    @Value("${netsight.ai.knowledge-topk:5}")
    private int topK;

    /** 问答结果缓存分钟数 */
    @Value("${netsight.ai.answer-cache-minutes:10}")
    private int cacheMinutes;

    /** 停用词（检索分词时剔除） */
    private static final List<String> STOP_WORDS = List.of(
            "怎么", "如何", "处理", "解决", "请问", "一下", "的", "了", "呢",
            "排查", "维修", "修复", "故障", "问题", "该怎么办", "怎么办", "为什么");

    private final AIService aiService;
    private final FaultArticleMapper faultArticleMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 知识库问诊入口
     *
     * @param request 问题（必填）+ 工单上下文（可选，前端自动带入）
     * @return 结构化答案（answer/sources/fromCache/aiUsed）
     */
    public AiAskResult ask(AiAskRequest request) {
        Long tenantId = SecurityUtils.getTenantId();
        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (!StringUtils.hasText(question)) {
            throw new com.netsight.common.exception.ServiceException(
                    com.netsight.common.core.ResultCode.PARAM_ERROR.getCode(), "请描述故障现象或问题");
        }

        // 1. 缓存命中直接返回（同租户同问题 10 分钟）
        String cacheKey = buildCacheKey(tenantId, question);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                AiAskResult cachedResult = JSONUtil.toBean(cached, AiAskResult.class);
                cachedResult.setFromCache(true);
                return cachedResult;
            } catch (Exception e) {
                // 缓存解析失败当作未命中，重新生成
                log.warn("AI 问诊缓存解析失败，重新生成: {}", e.getMessage());
            }
        }

        // 2. 检索知识库（当前租户 + 启用）
        List<FaultArticle> articles = retrieve(tenantId, question);
        if (articles.isEmpty()) {
            AiAskResult empty = AiAskResult.of(
                    "知识库暂无与当前故障现象相关的条目。建议：① 检查设备物理链路（网线/电源）；② 查看设备侧指示灯状态；③ 联系厂商技术支持。",
                    new ArrayList<>(), false, false);
            empty.setNoHit(true);
            return empty;
        }

        // 3. 拼 prompt 并生成
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request, articles);
        AiAskResult result;
        boolean aiUsed = false;
        try {
            AIRequest aiRequest = new AIRequest();
            aiRequest.setTenantId(tenantId);
            aiRequest.setFeature("knowledge_ask");
            aiRequest.setSystemPrompt(systemPrompt);
            aiRequest.setUserPrompt(userPrompt);
            aiRequest.setMaxTokens(800);
            String answer = aiService.complete(aiRequest);
            aiUsed = true;
            result = AiAskResult.of(answer, buildSources(articles), false, true);
        } catch (Exception e) {
            // 4. 降级：模型失败/不可用 → 返回 top1 文章原文
            log.warn("AI 问诊降级（{}），返回知识库原文: {}", e.getMessage(),
                    articles.get(0).getTitle());
            FaultArticle top = articles.get(0);
            String fallback = StrUtil.format(
                    "【AI 服务暂不可用，以下为知识库原文】\n\n《{}》\n\n可能原因：{}\n\n处理步骤：{}",
                    top.getTitle(),
                    nullToDash(top.getCause()),
                    nullToDash(top.getSolution()));
            result = AiAskResult.of(fallback, buildSources(articles), false, false);
        }

        // 5. 写缓存（仅缓存 AI 生成结果，原文降级不缓存避免知识更新滞后）
        if (aiUsed) {
            try {
                redisTemplate.opsForValue().set(cacheKey,
                        JSONUtil.toJsonStr(result),
                        Duration.ofMinutes(cacheMinutes));
            } catch (Exception e) {
                log.warn("AI 问诊缓存写入失败: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 检索当前租户启用中的知识条目：
     * 问题分词（空格/逗号切分 + 去停用词）→ 每个词与 keywords/title/symptom 做 OR LIKE
     * → 浏览量降序取 topK
     */
    private List<FaultArticle> retrieve(Long tenantId, String question) {
        List<String> keywords = tokenize(question);
        LambdaQueryWrapper<FaultArticle> wrapper = new LambdaQueryWrapper<FaultArticle>()
                .eq(FaultArticle::getStatus, 1)
                .eq(FaultArticle::getTenantId, tenantId)
                .orderByDesc(FaultArticle::getViewCount)
                .orderByDesc(FaultArticle::getId)
                .last("LIMIT " + topK);
        if (!keywords.isEmpty()) {
            wrapper.and(w -> {
                for (String kw : keywords) {
                    w.like(FaultArticle::getKeywords, kw)
                            .or().like(FaultArticle::getTitle, kw)
                            .or().like(FaultArticle::getSymptom, kw)
                            .or().like(FaultArticle::getCategory, kw);
                }
            });
        }
        return faultArticleMapper.selectList(wrapper);
    }

    /**
     * 分词：空格/逗号/顿号切分，剔除停用词与过短词（<2 字符），保留最多 6 个关键词
     */
    private List<String> tokenize(String text) {
        String[] parts = text.split("[\\s,，、。；;]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (!StringUtils.hasText(p) || p.length() < 2) {
                continue;
            }
            // 停用词剔除（整词）
            boolean stop = STOP_WORDS.stream().anyMatch(p::contains);
            if (stop && STOP_WORDS.contains(p)) {
                continue;
            }
            // 剔除停用词后的纯词（如"摄像头离线"→ 去掉"离线"？不：只剔纯停用词整词）
            result.add(p);
            if (result.size() >= 6) {
                break;
            }
        }
        return result;
    }

    /**
     * system prompt：角色 + 输出格式（可能原因 → 排查步骤 → 备件建议 → 参考文章）
     */
    private String buildSystemPrompt() {
        return "你是 NetSight 网络资产运维专家，面向现场维修工程师提供故障诊断辅助。"
                + "请严格基于下方提供的知识库条目回答问题，只引用给出的资料，不要编造知识库中没有的内容。"
                + "输出格式（用 Markdown）：\n"
                + "【可能原因】1-3 条，每条一句话\n"
                + "【排查步骤】编号列表，按现场可操作顺序，简明扼要\n"
                + "【备件建议】基于知识库内容与运维常识，列出可能需要准备/更换的备件或工具，注明仅供提前准备参考，最终以现场确认为准；若无明确备件则写\"暂无明显备件需求\"\n"
                + "【参考文章】列出引用的知识库文章标题。";
    }

    /**
     * user prompt：工单上下文（设备/位置/故障类型/描述）+ 补充现象 + 知识库条目
     */
    private String buildUserPrompt(AiAskRequest request, List<FaultArticle> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("维修工单背景：\n");
        if (StringUtils.hasText(request.getDeviceName())) {
            sb.append("- 设备：").append(request.getDeviceName());
            if (StringUtils.hasText(request.getDeviceType())) {
                sb.append("（").append(request.getDeviceType()).append("）");
            }
            sb.append("\n");
        }
        if (StringUtils.hasText(request.getDeviceLocation())) {
            sb.append("- 位置：").append(request.getDeviceLocation()).append("\n");
        }
        if (StringUtils.hasText(request.getFaultType())) {
            sb.append("- 故障类型：").append(request.getFaultType()).append("\n");
        }
        if (StringUtils.hasText(request.getDescription())) {
            sb.append("- 故障描述：").append(request.getDescription()).append("\n");
        }
        sb.append("现场补充现象：").append(request.getQuestion()).append("\n\n");

        sb.append("知识库条目（仅这些可用）：\n");
        for (int i = 0; i < articles.size(); i++) {
            FaultArticle a = articles.get(i);
            sb.append(i + 1).append(". 标题：").append(nullToDash(a.getTitle())).append("\n");
            sb.append("   现象：").append(nullToDash(a.getSymptom())).append("\n");
            sb.append("   原因：").append(nullToDash(a.getCause())).append("\n");
            sb.append("   方案：").append(nullToDash(a.getSolution())).append("\n");
        }
        return sb.toString();
    }

    /**
     * 引用文章列表（标题 + 分类，前端可点开）
     */
    private List<Map<String, Object>> buildSources(List<FaultArticle> articles) {
        return articles.stream().map(a -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("category", a.getCategory());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Redis 缓存 key：ai:ask:{tenantId}:{hash(question)}
     */
    private String buildCacheKey(Long tenantId, String question) {
        int hash = HashUtil.additiveHash(question, 31);
        return "ai:ask:" + tenantId + ":" + Math.abs(hash);
    }

    private String nullToDash(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }
}
