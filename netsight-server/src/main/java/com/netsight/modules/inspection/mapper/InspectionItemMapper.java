package com.netsight.modules.inspection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.inspection.entity.InspectionItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点检项库 Mapper
 */
@Mapper
public interface InspectionItemMapper extends BaseMapper<InspectionItem> {
}
