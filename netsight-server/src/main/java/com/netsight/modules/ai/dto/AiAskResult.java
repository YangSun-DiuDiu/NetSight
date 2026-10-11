package com.netsight.modules.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 知识库问诊结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAskResult {

    /** 模型/原文生成的答案（可能原因/排查步骤/备件建议/参考文章，Markdown） */
    private String answer;

    /** 引用文章列表（title/category/id，前端可点开） */
    private List<Map<String, Object>> sources;

    /** 是否命中缓存 */
    private boolean fromCache;

    /** 本次是否使用了模型（false=降级原文） */
    private boolean aiUsed;

    /** 知识库无命中（只提示不跑模型） */
    private boolean noHit;

    public static AiAskResult of(String answer, List<Map<String, Object>> sources,
                                  boolean fromCache, boolean aiUsed) {
        return AiAskResult.builder()
                .answer(answer)
                .sources(sources)
                .fromCache(fromCache)
                .aiUsed(aiUsed)
                .noHit(false)
                .build();
    }
}
