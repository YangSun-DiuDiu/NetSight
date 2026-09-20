package com.netsight.modules.system.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-权限关联表 Mapper（sys_role_permission）
 */
@Mapper
public interface SysRolePermissionMapper {

    /**
     * 查询角色拥有的权限标识
     */
    @Select("SELECT p.perm_key FROM sys_permission p " +
            "INNER JOIN sys_role_permission rp ON rp.permission_id = p.id " +
            "WHERE rp.role_id = #{roleId} AND p.del_flag = 0")
    List<String> selectPermKeysByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询角色拥有的权限ID列表
     */
    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 删除角色的全部权限绑定
     */
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 绑定角色权限
     */
    @Insert("INSERT INTO sys_role_permission(role_id, permission_id) VALUES(#{roleId}, #{permissionId})")
    int insertRolePerm(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
