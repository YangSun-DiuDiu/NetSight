package com.netsight.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.knowledge.entity.FaultArticle;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障知识库条目 Mapper
 */
@Mapper
public interface FaultArticleMapper extends BaseMapper<FaultArticle> {
}
