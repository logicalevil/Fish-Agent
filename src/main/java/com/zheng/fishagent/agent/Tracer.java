package com.zheng.fishagent.agent;

/**
 * 链路追踪的扩展点。
 * <p>
 * 核心框架<b>只负责在固定的结构边界上调用 {@link #start}</b>（一次 run、一次模型调用、
 * 一次工具执行）；至于 ID 怎么生成、span 要不要导出、导出到哪里，全部由实现决定。
 * 这样核心不必依赖 OpenTelemetry 之类的库，仍保持「零依赖」。
 *
 * <h2>默认行为</h2>
 * {@link #NOOP} 是默认值：它直接返回 {@link Span#NOOP}，不生成 ID、不压栈、不做任何事，
 * 因此<b>不设置 Tracer 时，追踪开销为零</b>。
 *
 * <h2>自定义实现</h2>
 * 需要接入日志或 OpenTelemetry 时，实现本接口并在
 * {@link ReActAgent.Builder#tracer(Tracer)} 处注入即可。内置的 {@link SimpleTracer}
 * 提供一个基于 UUID、把 span 打到日志的轻量实现。
 *
 * @see Span
 * @see SimpleTracer
 */
@FunctionalInterface
public interface Tracer {

    /** 默认实现：什么都不做。 */
    Tracer NOOP = (name, parent) -> Span.NOOP;

    /**
     * 创建（但不入栈）一个 span。
     * <p>
     * <b>不要</b>在这里维护「当前 span」——压栈与出栈由框架统一管理，实现方只需
     * 依据 {@code parent} 计算 ID 并返回一个 span 对象。
     *
     * @param name   操作名，如 {@code "agent.run"}、{@code "model.call"}、{@code "tool.execute"}
     * @param parent 父 span；当前处于根操作时为 {@link Span#NOOP}
     * @return 新建的 span；返回 {@code null} 或 {@link Span#NOOP} 表示本次不追踪
     */
    Span start(String name, Span parent);
}
