package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备拓扑关系实体
 * 对应表 device_topology：一台设备最多 1 主 + 2 备 上级（主备三路）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device_topology")
public class DeviceTopology extends BaseEntity {

    /** 下级设备ID */
    private Long deviceId;

    /** 主上级设备ID（必填） */
    private Long parentMainId;

    /** 备路1上级ID（选填） */
    private Long parentBackup1Id;

    /** 备路2上级ID（选填，最多2路备份） */
    private Long parentBackup2Id;

    /** 所属租户ID */
    private Long tenantId;
}
