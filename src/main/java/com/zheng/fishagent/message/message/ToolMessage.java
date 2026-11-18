package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.content.Content;

import java.util.List;
import java.util.Objects;

public final class ToolMessage extends BaseMessage {

    private final String toolCallId;              // ← 子类特有，与 ToolCall.id 配对
    private final String toolName;                // ← 子类特有

    public ToolMessage(List<Content> content, String toolCallId, String toolName) {
        super(Role.TOOL, content);
        this.toolCallId = toolCallId;
        this.toolName = Objects.requireNonNull(toolName, "toolName 不能为空");
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
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
        return "ToolMessage[toolName=" + toolName
                + ", toolCallId=" + toolCallId
                + ", contents=" + content() + "]";
    }

    @Override
    public String content() {
        return "";
    }
}
