package com.netsight.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.system.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 设备资产 Mapper
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @Select("SELECT * FROM device WHERE gateway_id = #{gatewayId} AND device_code = #{deviceCode} LIMIT 1")
    Device selectByGatewayAndCodeIncludingDeleted(@Param("gatewayId") Long gatewayId,
                                                   @Param("deviceCode") String deviceCode);

    @Update("UPDATE device SET del_flag = 0, update_time = NOW() WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
