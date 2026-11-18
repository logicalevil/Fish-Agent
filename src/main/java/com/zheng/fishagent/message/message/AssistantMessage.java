package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.message.content.Content;

import java.util.List;
import java.util.Objects;

public final class AssistantMessage extends BaseMessage {

    private final List<ToolCall> toolCalls;      // ← 子类特有

    public AssistantMessage(List<Content> content, List<ToolCall> toolCalls) {
        super(Role.ASSISTANT, content);
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        AssistantMessage that = (AssistantMessage) o;
        return toolCalls.equals(that.toolCalls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), toolCalls);
    }

    @Override
    public String toString() {
        return "AssistantMessage[contents=" + content() + ", toolCalls=" + toolCalls + "]";
    }

    @Override
    public String content() {
        return "";
    }
}
