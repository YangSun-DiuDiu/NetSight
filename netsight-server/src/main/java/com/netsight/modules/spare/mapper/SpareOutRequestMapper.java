package com.netsight.modules.spare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.spare.entity.SpareOutRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 备件出库申请单 Mapper
 */
@Mapper
public interface SpareOutRequestMapper extends BaseMapper<SpareOutRequest> {
}
