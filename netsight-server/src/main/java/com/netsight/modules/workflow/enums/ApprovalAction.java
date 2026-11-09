package com.netsight.modules.workflow.enums;

/**
 * 节点动作（审批轨迹里"这一行到底做了什么"）
 *
 * 与 WorkflowStatus 分开：状态回答"单子现在怎么样"，
 * 动作回答"这个人当时做了什么"。页面「我的结果」列只能用动作，不能用 skipType。
 */
public enum ApprovalAction {

    START("start", "发起", "info"),
    PASS("pass", "通过", "success"),
    REJECT("reject", "驳回", "danger"),
    CANCEL("cancel", "撤销", "info"),
    RETRIEVE("retrieve", "撤回", "info"),
    PENDING("pending", "待处理", "warning");

    private final String code;
    private final String text;
    private final String tag;

    ApprovalAction(String code, String text, String tag) {
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

    /**
     * 由历史行推导动作
     *
     * 关键：驳回走 terminationByInsId，落库 skip_type 仍是 PASS，
     * 所以不能看 skipType，只能看 flow_status + 单据终态。
     *
     * @param nodeType   节点类型：0=发起节点
     * @param flowStatus 引擎状态码
     * @param billStatus 单据业务状态
     */
    public static ApprovalAction resolve(Integer nodeType, String flowStatus, String billStatus) {
        if (nodeType != null && nodeType == 0) {
            return START;
        }
        if (flowStatus == null) {
            return PENDING;
        }
        switch (flowStatus) {
            case "9":
                return REJECT;
            case "4":
                return WorkflowStatus.REJECTED.getCode().equals(billStatus) ? REJECT : CANCEL;
            case "5":
            case "6":
            case "10":
                return CANCEL;
            case "7":
                return RETRIEVE;
            case "2":
            case "3":
            case "8":
                return PASS;
            default:
                return PENDING;
        }
    }
}
