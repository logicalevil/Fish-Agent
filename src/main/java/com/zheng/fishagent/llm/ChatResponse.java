package com.zheng.fishagent.llm;

import com.zheng.fishagent.message.Message;

/**
 * 模型返回结果。
 *
 * @param message      完整的 assistant 消息（流式调用时为聚合后的结果）
 * @param usage        token 用量
 * @param finishReason 结束原因，如 stop / length / max_iterations
 */
public record ChatResponse(Message message, TokenUsage usage, String finishReason) {

    public ChatResponse(Message message, TokenUsage usage) {
        this(message, usage, "stop");
    }

    public static ChatResponse of(Message message) {
        return new ChatResponse(message, TokenUsage.EMPTY, "stop");
    }

    public String text() {
        return message == null ? "" : message.content();
    }

    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }
}
