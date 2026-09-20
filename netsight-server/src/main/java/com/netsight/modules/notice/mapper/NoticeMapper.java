package com.netsight.modules.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.notice.entity.Notice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知公告 Mapper
 */
@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {
}
