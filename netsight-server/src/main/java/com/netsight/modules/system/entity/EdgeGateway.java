package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 边缘网关实体（本地监控栈）
 * 对应表 edge_gateway：网关注册、Token 鉴权、在线状态、链路类型
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("edge_gateway")
public class EdgeGateway extends BaseEntity {

    /** 网关唯一编码 */
    private String gatewayCode;

    /** 网关名称 */
    private String gatewayName;

    /** 部署位置 */
    private String location;

    /** 所属租户ID */
    private Long tenantId;

    /** 接入Token（网关上报鉴权，仅展示一次/可重置） */
    private String gatewayToken;

    /** PushPlus Token（网关级，发送优先级高于租户级；配置入口在边缘网关管理页） */
    private String pushplusToken;

    /** PushPlus Token 配置时间 */
    private LocalDateTime pushplusTokenTime;

    /** 上行链路类型：wired/wifi/4g5g */
    private String linkType;

    /** 最近上报IP */
    private String ipAddress;

    /** 在线状态：0离线/1在线（基于心跳判断） */
    private Integer onlineStatus;

    /** 最近心跳时间 */
    private LocalDateTime lastHeartbeatTime;

    /** 启用状态：1启用/0禁用（禁用后上报不接收） */
    private Integer status;
}
