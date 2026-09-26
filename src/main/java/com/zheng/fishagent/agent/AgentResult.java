package com.zheng.fishagent.agent;

import com.zheng.fishagent.llm.TokenUsage;
import com.zheng.fishagent.message.Message;

import java.util.List;

/**
 * 一次 Agent 运行的完整结果。
 *
 * @param text       最终回答
 * @param messages   截至本次运行结束时的会话消息（含本轮新增）
 * @param iterations 实际发生的模型调用轮数
 * @param usage      本次运行的 token 用量
 * @param stopReason 结束原因，见 {@link #STOP} 等常量
 * @param success    是否正常得到最终回答
 */
public record AgentResult(
        String text,
        List<Message> messages,
        int iterations,
        TokenUsage usage,
        String stopReason,
        boolean success) {

    /** 模型正常给出最终回答。 */
    public static final String STOP = "stop";
    /** 达到最大迭代轮数仍未得到最终回答。 */
    public static final String MAX_ITERATIONS = "max_iterations";
    /** 检测到重复的工具调用，提前终止。 */
    public static final String LOOP_DETECTED = "loop_detected";

    public AgentResult {
        text = text == null ? "" : text;
        messages = messages == null ? List.of() : List.copyOf(messages);
        usage = usage == null ? TokenUsage.EMPTY : usage;
    }

    /**
     * 是否因迭代预算耗尽而结束。等价于 {@code MAX_ITERATIONS.equals(stopReason())}。
     */
    public boolean reachedMaxIterations() {
        return MAX_ITERATIONS.equals(stopReason);
    }

    @Override
    public String toString() {
        return "AgentResult[iterations=" + iterations
                + ", stopReason=" + stopReason
                + ", usage=" + usage.totalTokens() + " tokens"
                + ", text=" + (text.length() > 120 ? text.substring(0, 120) + "..." : text) + "]";
    }
}
