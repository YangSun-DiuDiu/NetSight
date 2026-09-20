package com.netsight.modules.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.alert.entity.EventRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventRecordMapper extends BaseMapper<EventRecord> {
}
