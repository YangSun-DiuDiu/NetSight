package com.netsight.modules.spare.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 备品备件库
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("spare_part")
public class SparePart extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 备件编号 SP+时间戳+随机 */
    private String partNo;

    /** 类型 交换机/路由器/摄像头/NVR/门禁控制器/光纤模块/电源/网线等 */
    private String partType;

    /** 品牌 */
    private String brand;

    /** 型号 */
    private String model;

    /** 序列号 SN，唯一可追溯 */
    private String serialNo;

    /** 当前库存数量 */
    private Integer quantity;

    /** 单位 台/个/条/块 */
    private String unit;

    /** 状态 new全新/repairing返修中/repaired已修复 */
    private String status;

    /** 存放位置 */
    private String location;

    /** 安全库存阈值（低于标红预警） */
    private Integer safeStock;

    /** 入库时间 */
    private LocalDate inTime;

    /** 备注 */
    private String remark;

    /** 非表字段：低库存预警标记（列表回显） */
    @TableField(exist = false)
    private Boolean lowStock;
}
