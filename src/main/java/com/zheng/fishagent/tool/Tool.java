package com.zheng.fishagent.tool;

import java.util.Map;

/**
 * 一个可被 Agent 调用的工具。
 * <p>
 * 实现类只需提供名称、描述、参数 Schema 与执行逻辑；框架负责把描述上报给模型、
 * 并在模型发起调用时路由到 {@link #execute(Map)}。
 */
public interface Tool {

    String name();

    String description();

    /**
     * JSON Schema，形如 {@code {"type":"object","properties":{...},"required":[...]}}
     */
    Map<String, Object> parameters();

    /**
     * 执行工具。返回值会作为工具结果回填给模型，因此建议返回简洁、确定的文本。
     */
    String execute(Map<String, Object> arguments);

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parameters());
    }
}
