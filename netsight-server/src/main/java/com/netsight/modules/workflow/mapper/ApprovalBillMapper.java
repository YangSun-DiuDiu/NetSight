package com.netsight.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netsight.modules.workflow.entity.ApprovalBill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统一审批单 Mapper
 */
@Mapper
public interface ApprovalBillMapper extends BaseMapper<ApprovalBill> {
}
