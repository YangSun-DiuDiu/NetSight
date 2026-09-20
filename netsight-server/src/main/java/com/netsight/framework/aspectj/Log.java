package com.netsight.framework.aspectj;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解（方案 2.3 操作日志切面）
 * 标注在 Controller 层写操作方法（新增/修改/删除/状态变更等）上，
 * 由 {@link OperLogAspect} 拦截，记录操作人、操作时间、操作内容、IP、结果、耗时，
 * 复用事件中心 event_record 表（event_type = user_operation）统一存储与查询，不单独建日志表。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Log {

    /**
     * 模块名：如"用户管理"、"设备管理"、"工单管理"
     */
    String module() default "";

    /**
     * 操作描述：如"新增用户"、"派单"、"出入库"
     */
    String action() default "";
}
