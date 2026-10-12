package com.zheng.fishagent.agent;

/**
 * 一次可观测操作的「片段」（span）。
 * <p>
 * 一个 span 表示框架里一段有明确起止的操作：一次 {@code run}、一次模型调用、一次工具执行。
 * 它携带三个标识与若干属性，供日志、埋点、链路追踪使用，本身<b>不产生任何副作用</b>。
 *
 * <h2>三级 ID</h2>
 * <ul>
 *   <li>{@link #traceId()}：一次<b>逻辑请求</b>的全局标识，本次请求涉及的所有 span 共享它；</li>
 *   <li>{@link #spanId()}：<b>本次操作</b>的标识；</li>
 *   <li>{@link #parentSpanId()}：父操作的 spanId，根操作为 {@code null}。</li>
 * </ul>
 *
 * <h2>如何拿到「当前」的 ID</h2>
 * 使用者（尤其是 {@link AgentListener} 的实现）无需任何传参，直接调用 {@link #current()} 即可
 * 拿到正在执行的那个 span。这依赖框架「不创建线程」的前提——回调与 run 始终在同一线程上。
 * <p>
 * 未启用追踪（未给 {@link ReActAgent.Builder#tracer(Tracer)} 设置实现）时，一切返回
 * {@link #NOOP}，不生成 ID、不压栈，开销为零。
 */
public interface Span extends AutoCloseable {

    /** 空实现：未启用追踪时使用，所有操作都是 no-op。 */
    Span NOOP = new Span() {
        @Override
        public String traceId() {
            return "";
        }

        @Override
        public String spanId() {
            return "";
        }

        @Override
        public String parentSpanId() {
            return null;
        }

        @Override
        public Span attribute(String key, Object value) {
            return this;
        }

        @Override
        public void end() {
        }

        @Override
        public String toString() {
            return "Span[noop]";
        }
    };

    /** 逻辑请求级 ID；同一次请求的所有 span 相同。未启用追踪时为空串。 */
    String traceId();

    /** 本操作的 ID。未启用追踪时为空串。 */
    String spanId();

    /** 父操作的 spanId；根操作返回 {@code null}。 */
    String parentSpanId();

    /**
     * 附加一个可观测属性（如 {@code tool=weather}）。返回自身以便链式调用。
     */
    Span attribute(String key, Object value);

    /**
     * 结束本 span。实现方通常在此把 span 上报给日志或追踪系统；重复调用应为幂等。
     */
    void end();

    /**
     * {@link AutoCloseable} 适配，等价于 {@link #end()}，
     * 便于在 {@code try-with-resources} 中使用。
     */
    @Override
    default void close() {
        end();
    }

    /**
     * 返回当前线程正在执行的 span；若未处于任何 span 内或不追踪，返回 {@link #NOOP}。
     * <p>
     * <b>边界</b>：仅在「调用发生在本框架的 run 调用栈上」时有效。若你在回调里自行
     * 切换到别的线程，需把 span 作为值显式带过去，而不是再次调用本方法。
     */
    static Span current() {
        return Spans.current();
    }
}
