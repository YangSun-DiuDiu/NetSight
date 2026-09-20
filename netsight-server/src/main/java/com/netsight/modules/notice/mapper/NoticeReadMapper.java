package com.netsight.modules.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.notice.entity.NoticeRead;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告已读记录 Mapper
 */
@Mapper
public interface NoticeReadMapper extends BaseMapper<NoticeRead> {
}
