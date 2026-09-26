package com.zheng.fishagent.tool;

import java.util.Map;

/**
 * 一个可被 Agent 调用的工具。
 * <p>
 * 实现类只需提供名称、描述、参数 Schema 与执行逻辑；框架负责把描述上报给模型、
 * 并在模型发起调用时路由到 {@link #execute(Map)}。
 */
public interface Tool {

    /**
     * 工具名。同一次运行中必须唯一，模型通过它来选择工具。
     */
    String name();

    /**
     * 工具用途描述。直接决定模型能否正确选择工具，应写清「做什么、何时用」。
     */
    String description();

    /**
     * JSON Schema，形如 {@code {"type":"object","properties":{...},"required":[...]}}
     */
    Map<String, Object> parameters();

    /**
     * 执行工具。返回值会作为工具结果回填给模型，因此建议返回简洁、确定的文本。
     * <p>
     * 抛出异常是允许的：{@link ToolRegistry#execute} 会兜住并转成以「错误：」开头的文本回填，
     * 不会中断整个 run。
     */
    String execute(Map<String, Object> arguments);

    /**
     * 转成上报给模型的描述对象（名称 + 描述 + 参数 Schema）。
     */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parameters());
    }
}
