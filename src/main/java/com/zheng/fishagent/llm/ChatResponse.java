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

    /**
     * 缺省结束原因的便捷构造，等价于 {@code finishReason = "stop"}。
     */
    public ChatResponse(Message message, TokenUsage usage) {
        this(message, usage, "stop");
    }

    /**
     * 以「零用量 + stop」构造一个仅含消息的响应。
     */
    public static ChatResponse of(Message message) {
        return new ChatResponse(message, TokenUsage.EMPTY, "stop");
    }

    /**
     * 助手正文；{@code message} 为 {@code null} 时返回空串。
     */
    public String text() {
        return message == null ? "" : message.content();
    }

    /**
     * 本次响应是否请求了至少一个工具调用。
     */
    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }
}
