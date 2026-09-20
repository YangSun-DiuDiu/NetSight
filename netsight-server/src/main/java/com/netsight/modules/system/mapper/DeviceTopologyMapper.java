package com.netsight.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.system.entity.DeviceTopology;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备拓扑关系 Mapper
 */
@Mapper
public interface DeviceTopologyMapper extends BaseMapper<DeviceTopology> {
}
