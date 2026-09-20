package com.netsight.modules.notice.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公告已读记录
 * 用户点击公告详情自动标记已读（唯一键：租户+公告+用户）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("notice_read")
public class NoticeRead extends BaseEntity {

    /** 所属租户ID */
    private Long tenantId;

    /** 公告ID */
    private Long noticeId;

    /** 用户ID */
    private Long userId;

    /** 阅读时间 */
    private LocalDateTime readTime;
}
