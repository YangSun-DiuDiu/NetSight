package com.netsight.modules.inspection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.inspection.entity.InspectionRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点检记录 Mapper
 */
@Mapper
public interface InspectionRecordMapper extends BaseMapper<InspectionRecord> {
}
