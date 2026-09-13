package com.zheng.fishagent.agent;

import com.zheng.fishagent.message.Message;
import com.zheng.fishagent.message.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 组合监听器：把多个 {@link AgentListener} 合成一个，每个事件按注册顺序依次转发给全部被组合者。
 * <p>
 * 适用场景：一次 run 需要同时做多件事，例如 SSE 推送、日志落盘、埋点统计、测试断言。
 * {@link Agent#run(String, AgentListener)} 只接收单个监听器，因此「多监听器」属于调用方的编排问题，
 * 在调用侧先合成即可，无需改动框架接口：
 * <pre>{@code
 * AgentListener listeners = new CompositeAgentListener(sseListener, loggingListener, metricsListener);
 * agent.run("你好", listeners);
 * }</pre>
 * <p>
 * <b>顺序</b>：按传入顺序转发，先注册的先收到事件。
 * <p>
 * <b>异常</b>：不做隔离。任一被组合者抛出异常都会立即中断本次转发并向上传播（与普通 Java 调用语义一致），
 * 排在其后的监听器不再收到该事件。若需要「一个坏监听器不影响其他监听器」，请在组合之前自行用
 * {@code try/catch} 包裹该监听器。
 * <p>
 * <b>不可变</b>：构造时对传入集合做防御性拷贝，之后再修改原集合不会影响本实例；{@code null} 元素会被忽略。
 * 若过滤后没有任何监听器，本实例等价于 {@link AgentListener#NOOP}（各回调空转）。
 */
public final class CompositeAgentListener implements AgentListener {

    /** 被组合的监听器；构造后不再变更。 */
    private final List<AgentListener> delegates;

    /**
     * 以集合构造。
     *
     * @param listeners 被组合的监听器；为 {@code null} 或空表示不组合任何监听器（各回调空转）；
     *                  集合中的 {@code null} 元素会被忽略
     */
    public CompositeAgentListener(List<AgentListener> listeners) {
        List<AgentListener> copy = new ArrayList<>();
        if (listeners != null) {
            for (AgentListener listener : listeners) {
                if (listener != null) {
                    copy.add(listener);
                }
            }
        }
        this.delegates = List.copyOf(copy);
    }

    /**
     * 以变参构造，便于直接列举监听器。
     * <p>
     * 注意：{@code new CompositeAgentListener(null)} 会因与 {@link #CompositeAgentListener(List)} 产生
     * 重载歧义而编译失败；如需表达「无监听器」，请改用 {@code new CompositeAgentListener(List.of())}。
     *
     * @param listeners 被组合的监听器；{@code null} 元素会被忽略
     */
    public CompositeAgentListener(AgentListener... listeners) {
        this(listeners == null ? null : Arrays.asList(listeners));
    }

    @Override
    public void onStart(String userInput) {
        for (AgentListener delegate : delegates) {
            delegate.onStart(userInput);
        }
    }

    @Override
    public void onThought(String thought) {
        for (AgentListener delegate : delegates) {
            delegate.onThought(thought);
        }
    }

    @Override
    public void onToken(String token) {
        for (AgentListener delegate : delegates) {
            delegate.onToken(token);
        }
    }

    @Override
    public void onMessage(Message message) {
        for (AgentListener delegate : delegates) {
            delegate.onMessage(message);
        }
    }

    @Override
    public void onToolCall(ToolCall call) {
        for (AgentListener delegate : delegates) {
            delegate.onToolCall(call);
        }
    }

    @Override
    public void onToolResult(ToolCall call, String result) {
        for (AgentListener delegate : delegates) {
            delegate.onToolResult(call, result);
        }
    }

    @Override
    public void onFinish(AgentResult result) {
        for (AgentListener delegate : delegates) {
            delegate.onFinish(result);
        }
    }

    @Override
    public void onError(Throwable error) {
        for (AgentListener delegate : delegates) {
            delegate.onError(error);
        }
    }
}
