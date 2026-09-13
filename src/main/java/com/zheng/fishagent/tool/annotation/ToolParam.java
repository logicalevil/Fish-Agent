package com.zheng.fishagent.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述工具方法的参数，用于生成 JSON Schema。
 * <p>
 * 编译时需保留参数名（本工程已通过 {@code -parameters} 开启）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /**
     * 参数描述（等价于 {@link #description()}）。
     */
    String value() default "";

    /**
     * 参数名，默认取反射得到的形参名。
     */
    String name() default "";

    /**
     * 参数描述，优先级高于 {@link #value()}。
     */
    String description() default "";

    /**
     * 是否必填，默认必填。
     */
    boolean required() default true;
}
