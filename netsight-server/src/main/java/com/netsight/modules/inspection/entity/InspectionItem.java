package com.netsight.modules.inspection.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 点检项库
 * 点检模板：检查内容 + 检查标准 + 结果类型
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inspection_item")
public class InspectionItem extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 点检项名称 */
    private String itemName;

    /** 检查内容 */
    private String checkContent;

    /** 检查标准/达标要求 */
    private String checkStandard;

    /** 结果类型：check 勾选 / text 文本填写 */
    private String resultType;

    /** 状态：1 启用 / 0 停用 */
    private Integer status;

    /** 排序（越小越靠前） */
    private Integer sort;
}
