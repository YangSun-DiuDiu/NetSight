package com.netsight.modules.system.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.system.dto.SysUserOption;
import com.netsight.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户 Mapper
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按 id 查询用户并跳过租户拦截，用于“跨租户绑定”给出准确提示。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND del_flag = 0")
    SysUser selectByIdIgnoreTenant(@Param("id") Long id);

    /**
     * 选人候选：本租户、启用、有手机号、未绑定维修档案、非 super_admin。
     */
    @Select("""
            SELECT u.id, u.username, u.real_name AS realName, u.phone
            FROM sys_user u
            WHERE u.del_flag = 0
              AND u.status = 1
              AND u.tenant_id = #{tenantId}
              AND u.phone IS NOT NULL AND u.phone <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM repairer rp
                  WHERE rp.tenant_id = u.tenant_id AND rp.user_id = u.id AND rp.del_flag = 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  INNER JOIN sys_role r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.role_key = 'super_admin'
                    AND r.del_flag = 0 AND r.status = 1
              )
              AND (u.real_name LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.phone LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.username LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%'))
            ORDER BY u.id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<SysUserOption> selectRepairerUserOptions(@Param("tenantId") Long tenantId,
                                                  @Param("keyword") String keyword,
                                                  @Param("offset") long offset,
                                                  @Param("pageSize") long pageSize);

    @Select("""
            SELECT COUNT(1)
            FROM sys_user u
            WHERE u.del_flag = 0
              AND u.status = 1
              AND u.tenant_id = #{tenantId}
              AND u.phone IS NOT NULL AND u.phone <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM repairer rp
                  WHERE rp.tenant_id = u.tenant_id AND rp.user_id = u.id AND rp.del_flag = 0
              )
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user_role ur
                  INNER JOIN sys_role r ON r.id = ur.role_id
                  WHERE ur.user_id = u.id AND r.role_key = 'super_admin'
                    AND r.del_flag = 0 AND r.status = 1
              )
              AND (u.real_name LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.phone LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%')
                OR u.username LIKE CONCAT('%', IFNULL(#{keyword}, ''), '%'))
            """)
    long countRepairerUserOptions(@Param("tenantId") Long tenantId,
                                  @Param("keyword") String keyword);
}
