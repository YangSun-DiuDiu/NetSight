package com.netsight.modules.alert.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 维修人员创建时同步通知联系人的结果。
 */
@Data
@AllArgsConstructor
public class NotifyContactSyncResult {

    public enum Action {
        /** 新建联系人 */
        CREATED,
        /** 原联系人停用，已启用 */
        ENABLED,
        /** 已有启用联系人，直接复用 */
        EXISTING,
        /** 同手机号存在多条联系人，未自动处理 */
        AMBIGUOUS,
        /** 未选择接收通知 */
        SKIPPED,
        /** 手机号为空，未同步 */
        NO_PHONE
    }

    private Action action;
    private Long contactId;
    private String message;

    public static NotifyContactSyncResult created(Long contactId) {
        return new NotifyContactSyncResult(Action.CREATED, contactId, "通知联系人已新增");
    }

    public static NotifyContactSyncResult enabled(Long contactId) {
        return new NotifyContactSyncResult(Action.ENABLED, contactId, "通知联系人已启用");
    }

    public static NotifyContactSyncResult existing(Long contactId) {
        return new NotifyContactSyncResult(Action.EXISTING, contactId, "通知联系人已存在");
    }

    public static NotifyContactSyncResult ambiguous(String message) {
        return new NotifyContactSyncResult(Action.AMBIGUOUS, null, message);
    }

    public static NotifyContactSyncResult skipped() {
        return new NotifyContactSyncResult(Action.SKIPPED, null, "未选择接收通知");
    }

    public static NotifyContactSyncResult noPhone() {
        return new NotifyContactSyncResult(Action.NO_PHONE, null, "手机号为空，未同步通知联系人");
    }
}
