package com.netsight.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 故障知识库条目
 * 常见故障现象/原因/解决方案知识沉淀，供运维与维修人员查阅，与工单联动推荐
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fault_article")
public class FaultArticle extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 标题 */
    private String title;

    /** 分类（设备离线/链路异常/视频故障/门禁故障/性能问题/其他） */
    private String category;

    /** 适用设备类型 network/camera/nvr/door_controller/all */
    private String deviceType;

    /** 适用品牌（空=不限） */
    private String brand;

    /** 适用型号（空=不限） */
    private String model;

    /** 故障现象描述 */
    private String symptom;

    /** 可能原因 */
    private String cause;

    /** 处理步骤/解决方案 */
    private String solution;

    /** 关键词（逗号分隔） */
    private String keywords;

    /** 参考级别 critical/warning/info */
    private String severityRef;

    /** 浏览次数 */
    private Integer viewCount;

    /** 状态 1启用 0停用 */
    private Integer status;

    /** 创建人（快照） */
    private String createBy;
}
