package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 设备资产实体（网络/视频/门禁统一纳管）
 * 对应表 device
 * 状态三色推导：绿=status=1且lineStatus=0；红=status=0；灰=status=1且lineStatus=1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("device")
public class Device extends BaseEntity {

    /** 设备唯一编码（业务主键，自动生成） */
    private String deviceCode;

    /** 设备名称 */
    private String deviceName;

    /** 设备类型：network/camera/nvr/door_controller */
    private String deviceType;

    /** 品牌 */
    private String brand;

    /** 型号 */
    private String model;

    /** 管理IP */
    private String ipAddress;

    /** 部署位置 */
    private String location;

    /** 归属网关ID */
    private Long gatewayId;

    /** 所属租户ID */
    private Long tenantId;

    /** 运行状态（device_up）：1在线/0离线 */
    private Integer status;

    /** 外线状态（device_line_abnormal）：0正常/1异常 */
    private Integer lineStatus;

    /** 采集方式：snmp（默认）/probe（自研探针，国产化采集路径预留） */
    private String collectType;

    /** 采集端口：SNMP 默认 161 */
    private Integer collectPort;

    /** 保修截止日期（保修期，可空；空=未设置保修期） */
    private LocalDate warrantyExpire;

    /** 保修状态（列表回显派生，非表字段）：in_warranty=在保 / expired=已过保 / none=未设置 */
    @TableField(exist = false)
    private String warrantyStatus;

    /** 清空保修期标记（前端清空保修截止日期时传 true，非表字段） */
    @TableField(exist = false)
    private Boolean clearWarranty;

    /** 归属网关名称（列表回显，非表字段） */
    @TableField(exist = false)
    private String gatewayName;

    /** 拓扑上级设备ID：主上级/备1/备2（透传，非表字段） */
    @TableField(exist = false)
    private Long parentMainId;

    @TableField(exist = false)
    private Long parentBackup1Id;

    @TableField(exist = false)
    private Long parentBackup2Id;

    /** 上级设备名称列表（拓扑回显，非表字段） */
    @TableField(exist = false)
    private List<String> parentNames;
}
