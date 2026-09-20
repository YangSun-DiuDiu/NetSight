package com.netsight.modules.inspection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.inspection.entity.InspectionTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点检任务 Mapper
 */
@Mapper
public interface InspectionTaskMapper extends BaseMapper<InspectionTask> {
}
