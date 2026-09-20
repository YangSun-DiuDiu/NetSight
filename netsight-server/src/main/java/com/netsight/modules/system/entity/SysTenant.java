package com.netsight.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.netsight.common.core.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 租户实体
 * 对应表 sys_tenant（系统公共表，不参与租户过滤）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    /** 租户名称（公司/部门名） */
    private String tenantName;

    /** 联系人 */
    private String contactPerson;

    /** 联系电话 */
    private String contactPhone;

    /** 启用状态：1启用/0禁用 */
    private Integer status;

    /** 租户到期时间 */
    private LocalDateTime expireTime;

    /** Webhook 接入 Token（Token 即租户身份，服务端反查；AlertManager 告警上报鉴权用） */
    private String webhookToken;

    /** Webhook Token 生成/重置时间 */
    private LocalDateTime webhookTokenTime;

    /** PushPlus 通知 Token（租户级，云平台租户管理配置；PushPlus 通道发送时按租户取用） */
    private String pushplusToken;

    /** PushPlus Token 配置/更新/清空时间 */
    private LocalDateTime pushplusTokenTime;
}
