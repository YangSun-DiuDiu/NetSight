package com.netsight.modules.ai.dto;

import lombok.Data;

/**
 * 知识库问诊请求
 * question 为维修人员补充/输入的故障现象（必填）；
 * 工单上下文（设备/位置/故障类型/描述）由前端从工单详情自动带入（可选）
 */
@Data
public class AiAskRequest {

    /** 故障现象/问题（必填，作为检索关键词与 user prompt 主体） */
    private String question;

    /** 设备名称（工单自动带入） */
    private String deviceName;

    /** 设备类型（工单自动带入，如 camera/network） */
    private String deviceType;

    /** 设备位置（工单自动带入） */
    private String deviceLocation;

    /** 故障类型（工单自动带入，如 offline/line_abnormal/manual） */
    private String faultType;

    /** 故障描述（工单自动带入） */
    private String description;
}
