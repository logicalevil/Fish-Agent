package com.zheng.fishagent.core.message.message;

import com.zheng.fishagent.core.message.Role;
import com.zheng.fishagent.core.message.constant.MessageKeys;
import com.zheng.fishagent.core.message.content.Content;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolMessage extends BaseMessage {

    private final String toolCallId;              // ← 子类特有，与 ToolCall.id 配对
    private final String toolName;                // ← 子类特有

    public ToolMessage(List<Content> content, String toolCallId, String toolName) {
        super(Role.TOOL, content);
        this.toolCallId = toolCallId;
        this.toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
    }

    public static String toolCallId(Message message) {
        if(message instanceof ToolMessage){
            return ((ToolMessage)message).getToolCallId();
        }else{
            throw new IllegalArgumentException("message is not a tool message");
        }

    }
    public static String toolName(Message message) {
        if(message instanceof ToolMessage){
            return ((ToolMessage)message).toolName();
        }else{
            throw new IllegalArgumentException("message is not a tool message");
        }
    }

    public String getToolCallId() {
        return this.toolCallId;
    }

    public String toolName() {
        return this.toolName;
    }

    /**
     * 在公共字段之上追加 {@code toolName}；{@code toolCallId} 存在时才写入。
     */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put(MessageKeys.TOOL_NAME, toolName);
        if (toolCallId != null) {
            map.put(MessageKeys.TOOL_CALL_ID, toolCallId);
        }
        return map;
    }

    /**
     * 从映射还原工具结果消息，读取 {@code toolName} 与可选的 {@code toolCallId}。
     */
    public static ToolMessage fromMap(Map<String, Object> map) {
        return new ToolMessage(contentsOf(map),
                stringValue(map.get(MessageKeys.TOOL_CALL_ID)),
                stringValue(map.get(MessageKeys.TOOL_NAME)));
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        ToolMessage that = (ToolMessage) o;
        return Objects.equals(toolCallId, that.toolCallId)
                && toolName.equals(that.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), toolCallId, toolName);
    }

    @Override
    public String toString() {
        return "";
    }

    @Override
    public String allMessage() {
        return "";
    }
}
