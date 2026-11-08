package com.netsight.modules.workorder.dto;

import lombok.Data;

/**
 * 补建账号请求体：1 内聘绑定同手机号用户，2 外聘创建新账号。
 */
@Data
public class RepairerAccountRequest {

    private Integer repairerType;
}
