package com.netsight.modules.workorder.constant;

/**
 * 工单状态枚举（work_order.status）
 * 0待处理 1已派单 2维修中 3已完成 4已关闭 5已自动恢复
 */
public enum WorkOrderStatus {

    /** 待处理 */
    PENDING(0, "待处理"),
    /** 已派单 */
    DISPATCHED(1, "已派单"),
    /** 维修中 */
    REPAIRING(2, "维修中"),
    /** 已完成 */
    COMPLETED(3, "已完成"),
    /** 已关闭 */
    CLOSED(4, "已关闭"),
    /** 已自动恢复 */
    AUTO_RECOVERED(5, "已自动恢复");

    private final int code;
    private final String text;

    WorkOrderStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    /**
     * 按状态码取中文文案；未知值回退为原始数字字符串，null 返回 null
     * （与旧 Map.getOrDefault 行为一致）
     */
    public static String textOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (WorkOrderStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return String.valueOf(code);
    }
}
