package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户实体
 * 对应表 sys_user（带 tenant_id，参与租户过滤）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 所属租户ID（数据隔离关键字段） */
    private Long tenantId;

    /** 登录账号 */
    private String username;

    /** 密码（BCrypt加密） */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 手机号（PC端登录用） */
    private String phone;

    /** 微信OpenID（小程序登录用） */
    private String wechatOpenid;

    /** 状态：1正常/0禁用 */
    private Integer status;

    /** 角色ID列表（前端传参用，非表字段） */
    @TableField(exist = false)
    private List<Long> roleIds;
}
