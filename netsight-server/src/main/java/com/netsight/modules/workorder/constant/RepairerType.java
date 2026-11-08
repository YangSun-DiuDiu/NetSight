package com.netsight.modules.workorder.constant;

/**
 * 维修人员来源类型。
 */
public final class RepairerType {

    /** 存量未分类 */
    public static final int UNCLASSIFIED = 0;

    /** 园区内部人员，从用户管理选人绑定 */
    public static final int INTERNAL = 1;

    /** 外聘人员，建档时自动创建 sys_user */
    public static final int EXTERNAL = 2;

    private RepairerType() {
    }
}
