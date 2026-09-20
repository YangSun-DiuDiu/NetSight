package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 角色实体
 * 对应表 sys_role（系统公共表，不参与租户过滤）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    /** 角色名称 */
    private String roleName;

    /** 角色编码：super_admin/tenant_admin/ops/repairer */
    private String roleKey;

    /** 显示顺序 */
    private Integer roleSort;

    /** 状态：1正常/0停用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 权限ID列表（前端传参用，非表字段） */
    @TableField(exist = false)
    private List<Long> permIds;
}
