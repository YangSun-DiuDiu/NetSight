package com.netsight.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 维修人员库
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("repairer")
public class Repairer extends BaseEntity {

    /** 所属租户 */
    private Long tenantId;

    /** 维修人员编号 REP+时间戳+随机 */
    private String repairerNo;

    /** 姓名 */
    private String name;

    /** 手机号（短信通知） */
    private String phone;

    /** 微信公众号 OpenID */
    private String openid;

    /** 负责区域（楼宇/园区） */
    private String region;

    /** 负责设备类型 JSON 数组 [network,camera] */
    private String deviceTypes;

    /** 技能标签（逗号分隔） */
    private String skills;

    /** 状态 1在岗 0休假 2离职 */
    private Integer status;

    /** 备注 */
    private String remark;
}
