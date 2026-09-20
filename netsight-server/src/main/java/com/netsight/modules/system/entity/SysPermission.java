package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限实体（菜单/按钮/接口权限）
 * 对应表 sys_permission（系统公共表，不参与租户过滤）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    /** 父权限ID（0=顶级菜单） */
    private Long parentId;

    /** 权限名称 */
    private String permName;

    /** 权限标识：system:user:list */
    private String permKey;

    /** 类型：menu/button/api */
    private String permType;

    /** 排序 */
    private Integer sort;

    /** 路由路径（顶级 /system，子级相对路径 list） */
    private String path;

    /** 前端组件路径（顶级 Layout，子级 device/index） */
    private String component;

    /** 菜单图标（Element UI 图标名） */
    private String icon;
}
