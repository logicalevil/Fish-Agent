package com.zheng.fishagent.testkit;

import com.zheng.fishagent.core.message.ToolCall;
import com.zheng.fishagent.core.message.content.TextContent;
import com.zheng.fishagent.core.message.message.AssistantMessage;
import com.zheng.fishagent.core.message.message.SystemMessage;
import com.zheng.fishagent.core.message.message.ToolMessage;
import com.zheng.fishagent.core.message.message.UserMessage;

import java.util.List;

/**
 * 测试用消息构造快捷方法。
 * <p>
 * 生产代码里 {@link com.zheng.fishagent.core.message.message.Message} 只暴露读取方法，
 * 构造必须用具体子类；测试中大量重复的 {@code new XMessage(List.of(new TextContent(...)))}
 * 收拢到这里，让断言关注点保持在行为上。
 */
public final class Messages {

    private Messages() {
    }

    public static UserMessage user(String text) {
        return new UserMessage(List.of(new TextContent(text)));
    }

    public static SystemMessage system(String text) {
        return new SystemMessage(List.of(new TextContent(text)));
    }

    public static AssistantMessage assistant(String text) {
        return new AssistantMessage(List.of(new TextContent(text)), List.of());
    }

    public static AssistantMessage assistant(String text, List<ToolCall> toolCalls) {
        return new AssistantMessage(List.of(new TextContent(text)), toolCalls);
    }

    public static ToolMessage tool(String toolName, String content) {
        return new ToolMessage(List.of(new TextContent(content)), null, toolName);
    }

    public static ToolMessage tool(String toolCallId, String toolName, String content) {
        return new ToolMessage(List.of(new TextContent(content)), toolCallId, toolName);
    }
}
