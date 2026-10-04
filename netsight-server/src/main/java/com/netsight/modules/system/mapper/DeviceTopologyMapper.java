package com.netsight.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.system.entity.DeviceTopology;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设备拓扑关系 Mapper
 *
 * 注意：BaseEntity 带 @TableLogic 全局逻辑删除，BaseMapper.delete() 实际执行
 * UPDATE del_flag=2（逻辑删除），物理行仍占用唯一键 uk_topology_device(device_id)，
 * 导致"先删后插"保存拓扑时 insert 撞唯一键冲突。
 * 拓扑是纯关系数据（无审计/恢复价值），此处提供物理删除方法绕开逻辑删除。
 */
@Mapper
public interface DeviceTopologyMapper extends BaseMapper<DeviceTopology> {

    /**
     * 物理删除指定设备的拓扑行（含已逻辑删除的残留行）
     *
     * @param deviceId 设备ID
     * @return 影响行数
     */
    @Delete("DELETE FROM device_topology WHERE device_id = #{deviceId}")
    int physicalDeleteByDeviceId(@Param("deviceId") Long deviceId);

    /**
     * 物理删除引用了指定设备作为上级的拓扑行（删除设备时清理）
     *
     * @param id 设备ID（主/备上级引用）
     * @return 影响行数
     */
    @Delete("DELETE FROM device_topology WHERE parent_main_id = #{id} OR parent_backup1_id = #{id} OR parent_backup2_id = #{id}")
    int physicalDeleteByParentRef(@Param("id") Long id);
}
