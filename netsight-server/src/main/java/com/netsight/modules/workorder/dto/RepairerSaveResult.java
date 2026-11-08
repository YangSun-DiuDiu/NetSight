package com.netsight.modules.workorder.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 维修人员新增/补建账号返回体。
 */
@Data
@AllArgsConstructor
public class RepairerSaveResult {

    private Long id;
    private String loginUsername;
    private boolean accountCreated;
}
