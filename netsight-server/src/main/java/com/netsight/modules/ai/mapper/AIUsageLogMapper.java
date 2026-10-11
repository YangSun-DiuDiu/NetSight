package com.netsight.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.ai.entity.AIUsageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 用量审计 Mapper
 */
@Mapper
public interface AIUsageLogMapper extends BaseMapper<AIUsageLog> {
}
