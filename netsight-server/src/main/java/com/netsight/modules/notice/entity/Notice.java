package com.netsight.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知公告
 * 公告管理（发布/下线/置顶/过期）+ 用户端已读/未读
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notice")
public class Notice extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 公告标题 */
    private String title;

    /** 公告内容 */
    private String content;

    /** 类型：notice 公告 / notify 通知 */
    private String noticeType;

    /** 级别：normal 普通 / important 重要 / urgent 紧急 */
    private String level;

    /** 状态：0 草稿 / 1 已发布 / 2 已下线 */
    private Integer status;

    /** 是否置顶：0 否 / 1 是 */
    private Integer isTop;

    /** 发布时间 */
    private LocalDateTime publishTime;

    /** 过期时间（空=永久有效） */
    private LocalDateTime expireTime;

    /** 发布人用户ID */
    private Long publisherId;

    /** 发布人姓名（快照） */
    private String publisherName;

    /** 已读数（冗余统计） */
    private Integer readCount;
}
