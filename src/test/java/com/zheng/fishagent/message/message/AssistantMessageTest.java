package com.zheng.fishagent.message.message;

import com.zheng.fishagent.message.Role;
import com.zheng.fishagent.message.ToolCall;
import com.zheng.fishagent.message.content.Content;
import com.zheng.fishagent.message.content.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 {@link AssistantMessage} 上「从任意 {@link Message} 取工具调用」的静态入口。
 */
class AssistantMessageTest {

    private static final List<Content> TEXT = List.of(new TextContent("让我算一下"));

    private static AssistantMessage assistant(ToolCall... calls) {
        return new AssistantMessage(TEXT, List.of(calls));
    }

    private static Message user() {
        return new UserMessage(List.of(new TextContent("6 乘 7")));
    }

    private static Message tool() {
        return new ToolMessage(List.of(new TextContent("42")), "call-1", "multiply");
    }

    @Test
    void 助手消息应原样返回工具调用列表() {
        ToolCall multiply = ToolCall.of("multiply", Map.of("a", 6, "b", 7));
        AssistantMessage message = assistant(multiply);

        List<ToolCall> calls = AssistantMessage.toolCalls((Message) message);

        assertEquals(1, calls.size());
        assertEquals("multiply", calls.getFirst().name());
        assertEquals(Map.of("a", 6, "b", 7), calls.getFirst().arguments());
        assertEquals(multiply, calls.getFirst());
    }

    @Test
    void 工具调用列表为不可变副本() {
        AssistantMessage message = assistant(ToolCall.of("multiply", Map.of()));

        List<ToolCall> calls = AssistantMessage.toolCalls((Message) message);

        assertSame(calls, AssistantMessage.toolCalls(message), "同一实例应返回同一列表");
        assertThrows(UnsupportedOperationException.class,
                () -> calls.add(ToolCall.of("other", Map.of())));
    }

    @Test
    void 构造时传入null应视为无工具调用() {
        AssistantMessage message = new AssistantMessage(TEXT, null);

        assertTrue(AssistantMessage.toolCalls((Message) message).isEmpty());
        assertFalse(AssistantMessage.hasToolCalls(message));
    }

    @Test
    void 有工具调用时hasToolCalls为真() {
        AssistantMessage message = assistant(ToolCall.of("multiply", Map.of()));

        assertTrue(AssistantMessage.hasToolCalls(message));
    }

    @Test
    void 无工具调用时hasToolCalls为假() {
        assertFalse(AssistantMessage.hasToolCalls(new AssistantMessage(TEXT, List.of())));
    }

    @Test
    void 非助手消息取工具调用应抛异常且信息含实际类型() {
        Message user = user();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AssistantMessage.toolCalls(user));

        assertTrue(ex.getMessage().contains("UserMessage"),
                "异常信息应包含实际类型，实际为：" + ex.getMessage());
    }

    @Test
    void 非助手消息查询hasToolCalls应抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> AssistantMessage.hasToolCalls(tool()));
    }

    @Test
    void 传入null应快速失败() {
        assertThrows(IllegalArgumentException.class,
                () -> AssistantMessage.toolCalls(null));
    }

    @Test
    void 工具调用参与相等性比较() {
        ToolCall call = ToolCall.of("multiply", Map.of("a", 6, "b", 7));

        assertEquals(assistant(call), assistant(call));
        assertEquals(assistant(call).hashCode(), assistant(call).hashCode());
        assertFalse(assistant(call).equals(assistant()));
        assertFalse(assistant().equals(assistant(call)));
    }

    @Test
    void 助手消息角色为ASSISTANT() {
        assertEquals(Role.ASSISTANT, assistant().role());
    }
}
