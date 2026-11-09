package com.netsight.modules.workflow.enums;

/**
 * 统一审批状态（业务态）
 *
 * 全系统只认这四个字符串，引擎的 0~13 数字码不允许出现在接口与页面上。
 * 与 approval_bill.approval_status 的存量取值完全一致，不迁移数据。
 */
public enum WorkflowStatus {

    PROCESSING("processing", "审批中", "warning"),
    APPROVED("approved", "已通过", "success"),
    REJECTED("rejected", "已驳回", "danger"),
    CANCELED("canceled", "已撤销", "info");

    private final String code;
    private final String text;
    private final String tag;

    WorkflowStatus(String code, String text, String tag) {
        this.code = code;
        this.text = text;
        this.tag = tag;
    }

    public String getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public String getTag() {
        return tag;
    }

    /** 按业务状态字符串解析，未知返回 null */
    public static WorkflowStatus of(String code) {
        if (code == null) {
            return null;
        }
        for (WorkflowStatus value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 引擎状态 + 单据状态 → 业务状态（带兜底）
     *
     * 优先级：单据终态 > 引擎终态。
     * - 2 PASS / 3 AUTO_PASS 在多节点流程里只代表"当前节点通过"，不能据此判定整单通过；
     * - 8 FINISHED 才是整条流程结束；
     * - 4 TERMINATE 在引擎侧同时承载"驳回"与"撤销"，由单据状态消歧。
     */
    public static WorkflowStatus resolve(String engineStatus, String billStatus) {
        WorkflowStatus bill = of(billStatus);
        if (bill == null) {
            bill = PROCESSING;
        }
        if (bill == REJECTED || bill == CANCELED) {
            return bill;
        }
        if (engineStatus == null) {
            return bill;
        }
        switch (engineStatus) {
            case "8":
                return APPROVED;
            case "9":
                return REJECTED;
            case "4":
                return bill == REJECTED ? REJECTED : CANCELED;
            case "5":
            case "6":
            case "7":
            case "10":
                return CANCELED;
            // 0 待提交 / 1 审批中 / 2 单节点通过 / 3 自动完成 / 11 回退 / 12 重新开始 / 13 暂存
            default:
                return bill;
        }
    }
}
