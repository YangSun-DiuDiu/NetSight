package com.netsight.modules.workorder.dto;

import com.netsight.modules.alert.dto.NotifyContactSyncResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 维修人员新增/补建账号返回体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepairerSaveResult {

    private Long id;
    private String loginUsername;
    private boolean accountCreated;

    /** 创建维修人员时同步通知联系人的结果；修改/补建场景为 null */
    private NotifyContactSyncResult notifyContact;

    public RepairerSaveResult(Long id, String loginUsername, boolean accountCreated) {
        this(id, loginUsername, accountCreated, null);
    }
}
