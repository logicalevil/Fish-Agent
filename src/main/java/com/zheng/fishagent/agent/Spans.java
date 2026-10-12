package com.zheng.fishagent.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 环境上下文（ambient context）管理：维护「当前线程正在执行哪些 span」的栈。
 * <p>
 * <b>仅供框架内部使用。</b>把栈集中在这里，是为了让「压栈 / 出栈」只有一处实现，
 * 自定义 {@link Tracer} 的实现者不必（也不应）自己维护它。
 * <p>
 * 之所以能用线程局部变量：本框架<b>内部不创建任何线程</b>，一次 {@code run} 从头到尾
 * 跑在调用方的同一个线程上，因此回调里读到的「当前 span」必然是本次 run 的。
 * 栈在为空时会被 {@link ThreadLocal#remove()} 清理，避免线程池复用时串味。
 */
final class Spans {

    private static final ThreadLocal<Deque<Span>> STACK = new ThreadLocal<>();

    private Spans() {
    }

    /** 当前正在执行的 span；栈空或无追踪时为 {@link Span#NOOP}。 */
    static Span current() {
        Deque<Span> stack = STACK.get();
        return stack == null || stack.isEmpty() ? Span.NOOP : stack.peek();
    }

    /**
     * 开启一个 span 并压入栈：父节点取当前栈顶。
     * <p>
     * 未启用追踪（{@link Tracer#NOOP}）时返回 {@link Span#NOOP} 且<b>不压栈</b>，保证零开销。
     */
    static Span open(Tracer tracer, String name) {
        Span span = tracer.start(name, current());
        if (span == null || span == Span.NOOP) {
            return Span.NOOP;
        }
        Deque<Span> stack = STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            STACK.set(stack);
        }
        stack.push(span);
        return span;
    }

    /**
     * 关闭 span：先出栈，再触发 {@link Span#end()}（这样导出时「当前 span」已经回到父级）。
     * <p>
     * 必须与 {@link #open} 成对出现，通常写在 {@code finally} 里。
     */
    static void close(Span span) {
        if (span == null || span == Span.NOOP) {
            return;
        }
        Deque<Span> stack = STACK.get();
        if (stack == null) {
            return;
        }
        stack.remove(span);
        if (stack.isEmpty()) {
            // 栈空即清理，防止线程被复用时把上一次的上下文留给下一次
            STACK.remove();
        }
        span.end();
    }
}
