package com.netsight.modules.alert.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知联系人（接收人）
 * 单独维护的人员通讯录：短信通道用 mobile 投递，公众号/小程序通道用 wechat_openid 投递；
 * PushPlus 通道由租户 token 一对多群发，不依赖具体联系人。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notify_contact")
public class NotificationContact extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 姓名 */
    private String name;

    /** 手机号（短信通道投递地址） */
    private String mobile;

    /** 微信openid（公众号/小程序通道投递地址，授权绑定后回填；本期预留） */
    private String wechatOpenid;

    /** 备注 */
    private String remark;

    /** 状态 1启用/0停用 */
    private Integer status;

    /** 创建人（快照） */
    private String createBy;
}
