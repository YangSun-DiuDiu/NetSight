package com.netsight.modules.inspection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.inspection.entity.InspectionPlan;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点检计划 Mapper
 */
@Mapper
public interface InspectionPlanMapper extends BaseMapper<InspectionPlan> {
}
