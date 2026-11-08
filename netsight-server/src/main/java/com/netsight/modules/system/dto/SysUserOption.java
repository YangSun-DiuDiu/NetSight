package com.netsight.modules.system.dto;

import lombok.Data;

/**
 * 维修人员新增页的选人候选，只暴露姓名、手机号、登录账号。
 */
@Data
public class SysUserOption {

    private Long id;
    private String username;
    private String realName;
    private String phone;
}
