package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 驾驶舱上墙配置实体
 * 对应表 dashboard_wall_config：按租户保存「上墙模式（3×3）」勾选的大屏板块及顺序
 * 多租户隔离：tenant_id 由 MyBatis-Plus 租户插件自动注入（超管忽略过滤，保存时显式写入）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dashboard_wall_config")
public class DashboardWallConfig extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 板块标识（与前端 BOARD_DEFS.key 一一对应） */
    private String boardKey;

    /** 板块显示名称 */
    private String boardName;

    /** 上墙排序（1 起，越小越靠前） */
    private Integer sortOrder;
}
