package com.zheng.fishagent.message.constant;

import com.zheng.fishagent.message.message.Message;

/**
 * {@link Message#toMap()} 可移植映射表示中使用的键名。
 * <p>
 * 集中在一处，避免子类写映射与反序列化读映射时手写字面量拼错
 * （尤其 {@code toolCalls} 与 {@code toolCallId} 这类只差几个字符的键）。
 */
public final class MessageKeys {

    /** 消息角色，取值为 {@link com.zheng.fishagent.message.Role#value()}。 */
    public static final String ROLE = "role";
    /** 消息正文。 */
    public static final String CONTENT = "content";
    /** 工具调用列表，仅助手消息在存在调用时出现。 */
    public static final String TOOL_CALLS = "toolCalls";
    /** 工具名，仅工具消息出现。 */
    public static final String TOOL_NAME = "toolName";
    /** 工具调用标识，仅工具消息且标识存在时出现。 */
    public static final String TOOL_CALL_ID = "toolCallId";

    private MessageKeys() {
    }
}
