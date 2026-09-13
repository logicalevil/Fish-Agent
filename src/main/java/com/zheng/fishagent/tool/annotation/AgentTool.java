package com.zheng.fishagent.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个方法为 Agent 可调用的工具。
 * <pre>{@code
 * public class MyTools {
 *     @AgentTool("获取指定城市的当前时间")
 *     public String now(@ToolParam(value = "城市名", required = false) String city) {
 *         return ...;
 *     }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /**
     * 工具描述（等价于 {@link #description()}）。
     */
    String value() default "";

    /**
     * 工具名，默认取方法名。
     */
    String name() default "";

    /**
     * 工具描述，优先级高于 {@link #value()}。
     */
    String description() default "";
}
